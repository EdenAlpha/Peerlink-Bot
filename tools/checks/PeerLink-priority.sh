#!/system/bin/sh
# Run in PeerLink Prime Shell with a waiting room open, before joining a match.
# The visible second display is temporary. The report contains no game traffic.
umask 077
package=com.peerlink.app
engine=com.peerlink.app:botengine
game=jp.konami.pesam
key=overlay_display_devices
panel='320x480/160,own_content_only'
work=/data/local/tmp/peerlink-bot-priority

stop_check() { printf 'INCONCLUSIVE. %s\n' "$*"; exit 1; }

process_pid() {
  ps -A -n -o UID,PID,NAME | awk -v name="$1" -v user="$android_user" '
    $1 ~ /^[0-9]+$/ && int($1 / 100000) == user && $3 == name { pid=$2; n++ }
    END { if (n == 1) print pid; else exit 1 }'
}

score() {
  local value
  value=$(cat "/proc/$1/oom_score_adj" 2>/dev/null) || value=$(
    dumpsys -t 4 activity processes "${2%%:*}" | awk -v key=" $1:$2/" '
      $1 == "*APP*" || $1 == "*PERS*" { active=index($0,key) != 0 }
      active && $1 == "oom" && $2 == "adj:" {
        for (i=3; i<=NF; i++) if ($i ~ /^set=-?[0-9]+$/) { print substr($i,5); exit }
      }'
  )
  printf '%s\n' "$value" | awk '/^-?[0-9]+$/ && $1 >= -1000 && $1 <= 1000 { print; ok=1 } END { if (!ok) exit 1 }'
}

check_windows() {
  # visible-apps lists visible activity windows, their display IDs and input focus.
  dumpsys -t 5 window visible-apps | awk -v want="$1" -v game="$game/" -v peer="$package/" '
    $1 == "Display:" && $2 ~ /^mDisplayId=/ { display=substr($2,12); next }
    !windows && $1 ~ /^mCurrentFocus=/ && display == "0" { focus=index($0,game)>0 }
    $1 ~ /^mTopFocusedDisplayId=/ { split($1,k,"="); top=k[2] }
    /^WINDOW MANAGER WINDOWS/ { windows=1 }
    windows && $1 == "Window" && $2 ~ /^#[0-9]+$/ {
      peerWindow=index($0,peer)>0 && index($0,".ui.MainActivity")>0
      gameWindow=index($0,game)>0
    }
    windows && $1 ~ /^mDisplayId=[0-9]+$/ {
      id=substr($1,12)
      if (peerWindow) panels[id]=1
      if (gameWindow && id == "0") gameVisible=1
      peerWindow=0; gameWindow=0
    }
    END {
      for (id in panels) { count++; ids=ids " " id }
      printf "Game visible on main=%d, focused on main=%d, focused display=%s, PeerLink visible on display(s)=%s\n",
        gameVisible+0, focus+0, (length(top) ? top : "?"), (count ? ids : "none")
      if (!gameVisible || !focus || top != "0") exit 1
      if (want == "none" ? count != 0 : (count != 1 || !(want in panels))) exit 1
    }'
}

overlay_ids() {
  cmd display get-displays | awk '
    $1=="Display" && $2=="id" && $3 ~ /^[0-9]+:$/ {
      id=$3; sub(/:$/, "", id); if (id=="0") main=1
      if ($0 ~ /type OVERLAY([, }]|$)/) print id
    }
    END { if (!main) exit 1 }'
}

restore_display() {
  [ -f "$work/previous" ] || return 0
  local current previous
  previous=$(cat "$work/previous") || return 1
  current=$(settings get global "$key") || return 1
  if [ "$current" = "$panel" ]; then
    case "$previous" in
      null) settings delete global "$key" >/dev/null || return 1 ;;
      '') settings put global "$key" '' || return 1 ;;
      *) return 1 ;;
    esac
    current=$(settings get global "$key") || return 1
    [ "$current" = "$previous" ] || return 1
    echo 'Restored the original simulated-display setting.'
  elif [ "$current" != "$previous" ]; then
    echo 'The display setting changed during the check. Kept your current selection.'
  fi
  rm -f "$work/previous"
}

release_check() {
  if restore_display; then
    rm -f "$work/run.sh" "$work/pid" "$work/cancel"
    rmdir "$work"
  else
    echo 'Display restore needs attention. In Developer options, set Simulate secondary displays to None. Then run this script with restore.'
    return 1
  fi
}

open_game() {
  timeout 8 am start --user "$android_user" --display 0 -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER -p "$game" || stop_check 'eFootball could not be opened.'
}

check_phase() {
  local label="$1" display="$2" pass value game_value current_game
  low=1001; high=-1001
  printf '\n%s. Waiting %s seconds for Android to settle.\n' "$label" "$settle"
  sleep "$settle"
  for pass in 1 2 3; do
    [ ! -f "$work/cancel" ] || stop_check 'Check cancelled.'
    check_windows "$display" || stop_check 'The required windows or input focus could not be verified.'
    [ "$(process_pid "$engine")" = "$bot_pid" ] || stop_check 'The original bot process exited or changed.'
    current_game=$(process_pid "$game") || stop_check 'The main eFootball process could not be found.'
    if [ -z "$game_pid" ]; then game_pid=$current_game; fi
    [ "$game_pid" = "$current_game" ] || stop_check 'eFootball restarted during the check.'
    value=$(score "$bot_pid" "$engine") || stop_check 'Android did not expose a valid bot process score.'
    game_value=$(score "$game_pid" "$game") || stop_check 'Android did not expose a valid game process score.'
    printf '%s sample %s   bot PID %s score %s   eFootball PID %s score %s\n' \
      "$label" "$pass" "$bot_pid" "$value" "$game_pid" "$game_value"
    [ "$value" -ge "$low" ] || low=$value
    [ "$value" -le "$high" ] || high=$value
    [ "$pass" = 3 ] || sleep 2
  done
}

measure() {
  trap 'release_check' EXIT
  trap 'exit 1' INT TERM
  echo "$$" > "$work/pid"
  local previous ids display attempt settle grace base_low base_high panel_low panel_high
  local android_user="$1" bot_pid="$2" game_pid='' low high
  previous=$(settings get global "$key") || stop_check 'The display setting could not be read.'
  case "$previous" in ''|null) ;; *) stop_check 'A simulated display is already configured.' ;; esac
  ids=$(overlay_ids) || stop_check 'Android did not list its displays.'
  [ -z "$ids" ] || stop_check 'A simulated display is already active.'
  grace=$(dumpsys -t 4 activity settings | awk -F= '
    $1 ~ /^[[:space:]]*top_to_(fgs|almost_perceptible)_grace_duration$/ && $2 ~ /^[0-9]+$/ {
      if ($2 > max+0) max=$2
    }
    END { print (max+0 > 15000 ? max : 15000) }')
  [ "$grace" -le 120000 ] || stop_check 'This phone has a foreground grace period longer than two minutes.'
  settle=$(( (grace + 999) / 1000 + 5 ))
  echo 'PeerLink bot priority check. Lower scores receive stronger protection against low-memory kills.'
  open_game
  check_phase 'Before' none
  base_low=$low; base_high=$high

  [ "$(settings get global "$key")" = "$previous" ] || stop_check 'The display setting changed before the test.'
  [ ! -f "$work/cancel" ] || stop_check 'Check cancelled.'
  printf '%s\n' "$previous" > "$work/previous" || stop_check 'Could not save the display setting.'
  settings put global "$key" "$panel" || stop_check 'Android denied the simulated display.'
  display=''
  for attempt in 1 2 3 4 5; do
    ids=$(overlay_ids) || stop_check 'Android stopped reporting its displays.'
    case "$ids" in '') sleep 1 ;; *[!0-9]*) stop_check 'The new display could not be identified uniquely.' ;; *) display=$ids; break ;; esac
  done
  [ -n "$display" ] || stop_check 'Android did not create the simulated display.'
  timeout 8 am start --user "$android_user" --display "$display" -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER -n "$package/.ui.MainActivity" || stop_check 'Android could not open PeerLink on the simulated display.'
  sleep 2
  open_game
  check_phase 'With PeerLink visible' "$display"
  panel_low=$low; panel_high=$high

  [ "$(settings get global "$key")" = "$panel" ] || stop_check 'The simulated-display setting was changed separately during the check.'
  restore_display || stop_check 'Android did not confirm removal of the temporary display.'
  sleep 2
  open_game
  check_phase 'After removal' none
  printf '\nBot score ranges   before %s..%s   with window %s..%s   after removal %s..%s\n' \
    "$base_low" "$base_high" "$panel_low" "$panel_high" "$low" "$high"
  if [ "$panel_high" -lt "$base_low" ] && [ "$panel_high" -lt "$low" ]; then
    echo 'RESULT. Android gave the same bot process stronger protection while PeerLink was visible in this waiting room.'
  elif [ "$base_low" = "$base_high" ] && [ "$panel_low" = "$panel_high" ] && \
       [ "$low" = "$high" ] && [ "$low" = "$panel_low" ] && [ "$low" = "$base_low" ]; then
    echo 'RESULT. The visible PeerLink window made no measured difference to the bot score.'
  elif [ "$panel_low" -gt "$base_high" ] && [ "$panel_low" -gt "$high" ]; then
    echo 'RESULT. The bot had weaker protection while PeerLink was visible.'
  else
    echo 'INCONCLUSIVE. The scores varied or the change did not reverse after the window closed.'
  fi
}

case "${1-}" in
  --measure) [ "$0" = "$work/run.sh" ] || exit 1; measure "$2" "$3"; exit $? ;;
  restore)
    [ -d "$work" ] || { echo 'No test display to restore.'; exit 0; }
    worker=$(cat "$work/pid" 2>/dev/null)
    if [ -n "$worker" ] && ps -p "$worker" -o ARGS= 2>/dev/null | grep -Fq "$work/run.sh --measure"; then
      touch "$work/cancel"; echo 'Cancellation requested. The running check will restore the display.'; exit 0
    fi
    release_check; exit $? ;;
  '') ;;
  *) echo 'Run without arguments to check, or use restore after an interruption.'; exit 1 ;;
esac

android_user=$(timeout 3 am get-current-user) || stop_check 'Prime could not read the current Android user.'
case "$android_user" in ''|*[!0-9]*) stop_check 'Unexpected Android user ID.' ;; esac
bot_pid=$(process_pid "$engine") || stop_check 'First tap Create room and wait for the room number. Then run this command again.'
downloads="/storage/emulated/$android_user/Download"
report="$downloads/PeerLink-priority-$(date +%Y%m%d-%H%M%S)-$$.txt"
mkdir "$work" 2>/dev/null || stop_check 'A check is already active. After an interrupted check, run this script with restore.'
if ! mkdir -p "$downloads" || ! : > "$report" || ! cp "$0" "$work/run.sh"; then
  release_check; stop_check 'Prime could not prepare the report in Downloads.'
fi
(trap '' HUP; exec sh "$work/run.sh" --measure "$android_user" "$bot_pid") </dev/null >> "$report" 2>&1 &
printf 'Check started. Leave eFootball on screen until the report finishes.\nDownloads/%s\n' "${report##*/}"
