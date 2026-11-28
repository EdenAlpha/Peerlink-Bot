# Called by PeerLink over its authenticated Prime connection.
mode=$1
token=$2
peer=$3
if [ "${#token}" -ne 32 ]; then exit 2; fi
case "$token" in *[!0-9a-f]*) exit 2 ;; esac
case "$peer" in ''|*[!A-Za-z0-9_.]*) exit 2 ;; esac
shift 3
work=/data/local/tmp/peerlink-room-display-$token
game=jp.konami.pesam
umask 077

helper_alive() {
  local pid
  pid=$(cat "$work/helper-pid" 2>/dev/null) || return 1
  case "$pid" in ''|0|1|*[!0-9]*) return 1 ;; esac
  kill -0 "$pid" 2>/dev/null
}

display_id() {
  local display
  display=$(cat "$work/display-id" 2>/dev/null) || return 1
  case "$display" in ''|0|*[!0-9]*) return 1 ;; esac
  printf '%s\n' "$display"
}

score() {
  local pid="$1" name="$2" value
  if [ "$pid" = 0 ]; then printf unknown; return; fi
  if ! value=$(cat "/proc/$pid/oom_score_adj" 2>/dev/null); then
    value=$(dumpsys -t 2 activity processes "${name%%:*}" | awk -v key=" $pid:$name/" '
      $1=="*APP*" || $1=="*PERS*" { active=index($0,key) != 0 }
      active && $1=="oom" && $2=="adj:" {
        for (i=3; i<=NF; i++) if ($i ~ /^set=-?[0-9]+$/) { print substr($i,5); exit }
      }')
  fi
  case "$value" in ''|*[!0-9-]*) value=unknown ;; esac
  printf '%s' "$value"
}

case "$mode" in
  start)
    apk=$1
    task=$2
    if [ ! -r "$apk" ]; then
      echo 'DISPLAY_ERROR Prime could not read the display helper'
      exit 2
    fi
    case "$task" in ''|*[!0-9]*) exit 2 ;; esac
    user=$(am get-current-user) || exit 1
    case "$user" in ''|*[!0-9]*) exit 1 ;; esac
    mkdir "$work" || exit 1
    trap '[ ! -d "$work" ] || : > "$work/display-stop"' EXIT
    : > "$work/lease" || exit 1
    (
      trap '' HUP
      CLASSPATH="$apk" exec app_process /system/bin --nice-name=peerlink_room_display \
        com.peerlink.app.godmode.PeerLinkOffscreenDisplay "$work" room
    ) </dev/null > "$work/display-helper.log" 2>&1 &
    printf '%s\n' "$!" > "$work/helper-pid" || exit 1
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16; do
      display=$(display_id) && break
      helper_alive || break
      sleep 0.25
    done
    if [ -z "$display" ]; then
      reason=$(sed -n '/Exception:\|Error:/{p;q;}' "$work/display-helper.log" 2>/dev/null)
      printf 'DISPLAY_ERROR %s\n' "${reason:-Android did not register the off-screen display}"
      exit 1
    fi
    launch_status=0
    timeout 4 am start -W --user "$user" --display "$display" -f 0x18000000 \
        -n "$peer/com.peerlink.app.godmode.RoomDisplayActivity" --es room_token "$token" > "$work/launch.log" 2>&1 || launch_status=$?
    # Zero selects the Android 15 path, which leaves the current task focused.
    if [ "$task" != 0 ]; then
      timeout 2 am task focus "$task" >/dev/null 2>&1 || echo 'Could not restore the main PeerLink task.'
    fi
    if [ "$launch_status" != 0 ]; then
      reason=$(sed -n '/Exception:\|Error:\|Permission Denial/{p;q;}' "$work/launch.log")
      printf 'DISPLAY_ERROR %s\n' "${reason:-Android did not open the room activity (exit $launch_status)}"
      exit 1
    fi
    printf 'DISPLAY_ID=%s\n' "$display"
    trap - EXIT
    ;;
  sample)
    bot_pid=$1
    case "$bot_pid" in ''|*[!0-9]*) exit 2 ;; esac
    helper_alive || exit 1
    display=$(display_id) || exit 1
    [ -f "$work/lease" ] && [ ! -f "$work/display-stop" ] && touch -c "$work/lease" || exit 1
    memory=$(awk '/^MemAvailable:|^SwapFree:/ { printf "%s%.0fMiB ", $1, $2/1024 }' /proc/meminfo)
    game_pid=$(pidof "$game" 2>/dev/null)
    case "$game_pid" in ''|*[!0-9]*) game_pid=0 ;; esac
    printf 'DISPLAY_SAMPLE display=%s botPid=%s botAdj=%s gamePid=%s gameAdj=%s %s\n' \
      "$display" "$bot_pid" "$(score "$bot_pid" "$peer:botengine")" \
      "$game_pid" "$(score "$game_pid" "$game")" "$memory"
    ;;
  stop)
    if [ -d "$work" ]; then
      : > "$work/display-stop" || exit 1
      for attempt in 1 2 3 4 5 6 7 8; do
        [ -s "$work/display-id" ] && helper_alive || break
        sleep 0.25
      done
      if [ -s "$work/display-id" ] && helper_alive; then exit 1; fi
      rm -f "$work/lease" "$work/display-id" "$work/display-id.pending" "$work/helper-pid" \
        "$work/display-helper.log" "$work/display-stop" "$work/launch.log"
      rmdir "$work" 2>/dev/null
    fi
    echo DISPLAY_CLOSED
    ;;
  *) exit 2 ;;
esac
