#!/system/bin/sh
# Run in PeerLink Prime Shell with a waiting room open.
# With no argument, compare priority before, during and after the second display.
# With match, hold the display through gameplay and record priority and memory.
# With restore, stop the running check and restore its temporary display setting.
umask 077
package=com.peerlink.app
engine=com.peerlink.app:botengine
game=jp.konami.pesam
key=overlay_display_devices
panel='320x480/160,own_content_only'
work=/data/local/tmp/peerlink-bot-priority

stop_check() { printf 'INCONCLUSIVE. %s\n' "$*"; exit 1; }

display_setting() {
  local reply status value
  reply=$(timeout 5 settings get global "$key" 2>&1); status=$?
  if [ "$status" -eq 0 ]; then
    case "$reply" in ''|null|[0-9]*x[0-9]*/*) printf '%s\n' "$reply"; return 0 ;; esac
  fi
  # Standalone Android cmd can send Binder errors to stdout. Keep the reason
  # outside the value returned to the caller. This query requests just one key.
  printf 'settings get %s (exit %s): %.500s\n' "$key" "$status" "${reply:-no output}" >&2
  reply=$(timeout 5 cmd settings list global 2>&1); status=$?
  if [ "$status" -eq 0 ]; then
    value=$(printf '%s\n' "$reply" | awk -v key="$key=" '
      index($0,key)==1 { n++; value=substr($0,length(key)+1) }
      /^(adb_enabled|airplane_mode_on|device_provisioned|development_settings_enabled)=/ { table=1 }
      END {
        if (n==1) print value
        else if (n==0 && table) print "null"
        else exit 1
      }') && { printf '%s\n' "$value"; return 0; }
  fi
  # Only print errors, never the phone's global settings table.
  printf 'cmd settings list global (exit %s) could not verify the setting.\n' "$status" >&2
  printf '%s\n' "$reply" | awk '/^[[:space:]]*(cmd:|Error:|Exception|SecurityException|Permission [Dd]enial|java\.[a-z.]+Exception:|Can.t find service|timeout:)/ {print substr($0,1,500); exit}' >&2
  return 1
}

write_display_setting() {
  local reply status
  reply=$(timeout 5 cmd settings "$@" 2>&1) && return 0
  status=$?
  printf 'cmd settings %s %s %s (exit %s): %.500s\n' "$1" "$2" "$3" "$status" "${reply:-no output}" >&2
  return 1
}

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
      active && !found && $1 == "oom" && $2 == "adj:" {
        for (i=3; i<=NF; i++) if ($i ~ /^set=-?[0-9]+$/) { print substr($i,5); found=1; break }
      }'
  )
  printf '%s\n' "$value" | awk '/^-?[0-9]+$/ && $1 >= -1000 && $1 <= 1000 { print; ok=1 } END { if (!ok) exit 1 }'
}

check_windows() {
  # Activity focus and window input focus differ when Android draws a system window.
  dumpsys -t 5 window visible-apps | awk -v want="$1" -v game="$game" -v peer="$package" '
    function component_owner(line, result) {
      if (!match(line, /[A-Za-z0-9_.]+\/[A-Za-z0-9_.$]+/)) return ""
      result=substr(line,RSTART,RLENGTH)
      sub(/\/.*/, "", result)
      return result
    }
    function window_id(line) {
      if (!match(line, /Window\{[[:xdigit:]]+/)) return ""
      return substr(line,RSTART+7,RLENGTH-7)
    }
    !windows && $1 == "Display:" && $2 ~ /^mDisplayId=/ { display=substr($2,12); next }
    !windows && display == "0" && $1 ~ /^mCurrentFocus=/ {
      inputId=window_id($0)
      input=component_owner($0)
      if ($1 == "mCurrentFocus=null") input="none"
      if (input=="" && $0 ~ /Keyguard/) input="lock-screen"
      if (input=="" && $0 ~ /NotificationShade/) input="notification-shade"
      if (input=="" && $0 ~ /StatusBar|NavigationBar/) input="system-bars"
      if (input=="" && $0 ~ /InputMethod/) input="keyboard"
    }
    !windows && display == "0" && $1 ~ /^mFocusedApp=/ {
      app=component_owner($0)
      if ($1 == "mFocusedApp=null") app="none"
    }
    !windows && display == "0" && $1 ~ /^mAwake=(true|false)$/ {
      awake=substr($1,8)
      for (i=2; i<=NF; i++) if ($i ~ /^mScreenOnFully=(true|false)$/) screen=substr($i,16)
    }
    $1 ~ /^mTopFocusedDisplayId=[0-9]+$/ { top=substr($1,22) }
    /^WINDOW MANAGER WINDOWS/ { windows=1 }
    windows && $1 == "Window" && $2 ~ /^#[0-9]+$/ {
      id=window_id($0)
      owner=component_owner($0)
      mainActivity=(owner == peer && index($0,".ui.MainActivity")>0)
      windowDisplay=""
    }
    windows && id!="" && $1 ~ /^mDisplayId=[0-9]+$/ {
      windowDisplay=substr($1,12)
      if (mainActivity) panels[windowDisplay]=1
      if (owner == game && windowDisplay == "0") gameVisible=1
    }
    windows && id!="" && windowDisplay!="" && $1 ~ /^mOwnerUid=/ {
      for (i=2; i<=NF; i++) {
        if ($i !~ /^package=[A-Za-z0-9_.]+$/) continue
        owner=substr($i,9)
        if (id == inputId) input=owner
        if (owner == game && windowDisplay == "0") gameVisible=1
      }
    }
    windows && id!="" && windowDisplay!="" && $1 ~ /^mActivityRecord=/ {
      if (component_owner($0)==peer && $0 ~ /\.ui\.MainActivity([[:space:]}]|$)/) panels[windowDisplay]=1
    }
    END {
      for (id in panels) { count++; ids=ids " " id }
      printf "input=%s, foreground app=%s, game visible=%d, focused display=%s, screen on=%s, awake=%s, PeerLink displays=%s\n",
        (input!="" ? input : "unknown"), (app!="" ? app : "unknown"), gameVisible+0,
        (top!="" ? top : "?"), (screen!="" ? screen : "?"), (awake!="" ? awake : "?"),
        (count ? ids : "none")
      if (!gameVisible || top != "0" || awake == "false" || screen == "false" || input == "lock-screen") exit 1
      if (app != game && (app != "" || input != game)) exit 1
      if (input != game && awake != "true" && screen != "true") exit 1
      if (want != "game" && (want == "none" ? count != 0 : (count != 1 || !(want in panels)))) exit 1
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
  current=$(display_setting) || return 1
  if [ "$current" = "$panel" ]; then
    case "$previous" in
      null) write_display_setting delete global "$key" || return 1 ;;
      '') write_display_setting put global "$key" '' || return 1 ;;
      *) return 1 ;;
    esac
    current=$(display_setting) || return 1
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

wait_for_game_foreground() {
  local remaining="$1"
  while [ "$remaining" -gt 0 ]; do
    [ ! -f "$work/cancel" ] || stop_check 'Check cancelled.'
    check_windows game >/dev/null && return 0
    remaining=$((remaining - 1))
    [ "$remaining" -eq 0 ] || sleep 3
  done
  return 1
}

game_task() {
  # A live activity record identifies the Android user, component and task together.
  dumpsys -t 4 activity activities | awk -v user="u$android_user" -v pkg="$game/" '
    $1 == "Display" && $2 ~ /^#[0-9]+$/ { main=($2=="#0"); next }
    main && !found && /ActivityRecord\{/ && (/Hist[[:space:]]+#[0-9]+:/ || /topResumedActivity=/) {
      for (i=2; i<NF; i++) {
        if ($(i-1)==user && index($i,pkg)==1 && $(i+1) ~ /^t[0-9]+}$/) {
          print substr($(i+1),2,length($(i+1))-2); found=1; break
        }
      }
    }'
}

launch_game() {
  printf 'Opening %s on the main screen.\n' "$1"
  timeout 8 am start --user "$android_user" --display 0 -a android.intent.action.MAIN \
    -c android.intent.category.LAUNCHER -f 0x10200000 -n "$1" && wait_for_game_foreground 3
}

open_game() {
  local task launcher
  local stock_launcher="$game/com.epicgames.ue4.SplashActivity"
  if [ -n "$game_pid" ] && [ "$(process_pid "$game")" != "$game_pid" ]; then
    stop_check 'eFootball restarted or exited during the check.'
  fi
  wait_for_game_foreground 1 && return 0
  task=$(game_task)
  if [ -n "$task" ]; then
    printf 'Bringing the existing eFootball task %s to the front.\n' "$task"
    if timeout 5 am task focus "$task" && wait_for_game_foreground 2; then return 0; fi
  elif [ -z "$game_pid" ]; then
    launcher=$(timeout 5 cmd package resolve-activity --brief --user "$android_user" \
      -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p "$game" |
      awk -v pkg="$game" '!found && NF==1 && index($1,pkg "/")==1 && $1 ~ /\/[A-Za-z0-9._$]+$/ {print $1; found=1}')
    if [ -n "$launcher" ] && launch_game "$launcher"; then return 0; fi
    if [ "$launcher" != "$stock_launcher" ] && launch_game "$stock_launcher"; then return 0; fi
  fi
  echo 'Waiting up to one minute for you to bring eFootball to the main screen.'
  wait_for_game_foreground 21 && return 0
  check_windows game || :
  stop_check 'eFootball could not be verified in the foreground on the main screen.'
}

check_phase() {
  local label="$1" display="$2" value game_value current_game
  local state last_state='' ready input pass=0 elapsed=0 delay=4
  local attempt=0 limit=$(( (settle + 3) / 4 + 12 ))
  low=1001; high=-1001
  phase_input=''
  printf '\n%s. Measuring after %s seconds with eFootball in front.\n' "$label" "$settle"
  while [ "$attempt" -lt "$limit" ]; do
    attempt=$((attempt + 1))
    [ ! -f "$work/cancel" ] || stop_check 'Check cancelled.'
    [ "$(process_pid "$engine")" = "$bot_pid" ] || stop_check 'The original bot process exited or changed.'
    ready=0
    state=$(check_windows "$display") && ready=1
    input=${state%%,*}; input=${input#input=}
    if [ "$state" != "$last_state" ] || [ "$attempt" = "$limit" ]; then
      value=$(score "$bot_pid" "$engine") || value=unavailable
      printf 'Observed bot score %s. %s\n' "$value" "$state"
      last_state=$state
    fi
    current_game=$(process_pid "$game")
    if [ -n "$game_pid" ] && [ "$game_pid" != "$current_game" ]; then
      stop_check 'eFootball restarted or exited during the check.'
    fi
    if [ "$ready" -eq 0 ] || [ -z "$current_game" ]; then
      elapsed=0; pass=0; low=1001; high=-1001; phase_input=''
    else
      game_pid=$current_game
      if [ "$input" != "$phase_input" ]; then
        elapsed=0; pass=0; low=1001; high=-1001; phase_input=$input
      fi
      if [ "$elapsed" -ge "$settle" ]; then
        value=$(score "$bot_pid" "$engine") || stop_check 'Android did not expose a valid bot process score.'
        game_value=$(score "$game_pid" "$game") || stop_check 'Android did not expose a valid game process score.'
        pass=$((pass + 1))
        printf '%s sample %s   bot PID %s score %s   eFootball PID %s score %s   input %s\n' \
          "$label" "$pass" "$bot_pid" "$value" "$game_pid" "$game_value" "$input"
        [ "$value" -ge "$low" ] || low=$value
        [ "$value" -le "$high" ] || high=$value
        [ "$pass" -lt 3 ] || return 0
      fi
      elapsed=$((elapsed + delay))
    fi
    sleep "$delay"
  done
  stop_check "The \"$label\" phase did not get three stable readings with eFootball in front and the expected PeerLink windows."
}

record_exit() {
  local package_name="$1" pid="$2" delay history
  for delay in 0 2 4; do
    [ "$delay" -eq 0 ] || sleep "$delay"
    history=$(dumpsys -t 4 activity exit-info "$package_name" 2>&1) || continue
    if printf '%s\n' "$history" | awk -v key="pid=$pid" '
      /^[[:space:]]*ApplicationExitInfo #[0-9]+:/ { show=0 }
      {
        for (i=1; i<=NF; i++) if ($i == key) { show=1; found=1 }
        if (show) print
      }
      END { if (!found) exit 1 }
    '; then
      return 0
    fi
  done
  printf 'Android returned no exit record for %s PID %s during this check.\n' "$package_name" "$pid"
}

record_match() {
  local display="$1" sample=0 current_bot current_game bot_adj game_adj memory timestamp
  local uptime ignored deadline
  read -r uptime ignored < /proc/uptime || stop_check 'Android did not expose its uptime.'
  deadline=$(( ${uptime%%.*} + 900 ))
  printf '\nWindow ready. Join the room and play one match. Recording for up to 15 minutes.\n'
  while read -r uptime ignored < /proc/uptime && [ "${uptime%%.*}" -lt "$deadline" ]; do
    if [ -f "$work/cancel" ]; then
      echo 'Recording stopped from Prime Shell.'
      return 0
    fi
    timestamp=$(date '+%H:%M:%S')
    current_bot=$(process_pid "$engine")
    current_game=$(process_pid "$game")
    if [ "$current_bot" != "$bot_pid" ] || [ "$current_game" != "$game_pid" ]; then
      printf '%s Process changed   bot initial=%s current=%s   game initial=%s current=%s\n' \
        "$timestamp" "$bot_pid" "${current_bot:-unavailable}" "$game_pid" "${current_game:-unavailable}"
      check_windows "$display" || :
      [ "$current_bot" = "$bot_pid" ] || record_exit "$package" "$bot_pid"
      [ "$current_game" = "$game_pid" ] || record_exit "$game" "$game_pid"
      return 0
    fi
    bot_adj=$(score "$bot_pid" "$engine") || bot_adj=unavailable
    game_adj=$(score "$game_pid" "$game") || game_adj=unavailable
    memory=$(awk '/^(MemAvailable|SwapFree):/ { printf "%s%.0fMiB ", $1, $2/1024 }' /proc/meminfo)
    printf '%s   bot PID %s score %s   eFootball PID %s score %s   %s\n' \
      "$timestamp" "$bot_pid" "$bot_adj" "$game_pid" "$game_adj" "$memory"
    if [ $((sample % 3)) -eq 0 ]; then
      check_windows "$display" || :
    fi
    sample=$((sample + 1))
    sleep 10
  done
  echo 'Recording reached its time limit.'
}

measure() {
  trap 'release_check' EXIT
  trap 'exit 1' INT TERM
  echo "$$" > "$work/pid"
  local previous initial_display_setting ids display attempt settle grace base_low base_high panel_low panel_high
  local android_user="$1" bot_pid="$2" mode="$3" game_pid='' low high phase_input baseline_input panel_input
  printf 'PeerLink priority check v6 (%s). Android user %s, bot PID %s. Lower scores receive stronger protection against low-memory kills.\n' "$mode" "$android_user" "$bot_pid"
  local initial_score
  if initial_score=$(score "$bot_pid" "$engine"); then
    printf 'At script start, before verifying the game screen: bot score %s (outside the comparison).\n' "$initial_score"
  fi
  if [ "$mode" = compare ]; then
    grace=$(dumpsys -t 4 activity settings | awk -F= '
      $1 ~ /^[[:space:]]*top_to_(fgs|almost_perceptible)_grace_duration$/ && $2 ~ /^[0-9]+$/ {
        if ($2 > max+0) max=$2
      }
      END { print (max+0 > 15000 ? max : 15000) }')
    [ "$grace" -le 120000 ] || stop_check 'This phone has a foreground grace period longer than two minutes.'
    settle=$(( (grace + 999) / 1000 + 5 ))
  fi
  previous=$(display_setting) || previous=unavailable
  case "$previous" in
    ''|null) echo 'Simulated display setting is off at startup.' ;;
    unavailable) echo 'Android could not verify the simulated display setting at startup.' ;;
    *) echo 'A simulated display is already configured at startup.' ;;
  esac
  open_game
  if [ "$mode" = match ]; then
    # Opening the window for a live match only requires the two game processes.
    # A foreground comparison is already available from the separate check.
    for attempt in 1 2 3 4 5; do
      game_pid=$(process_pid "$game") && break
      [ "$attempt" -eq 5 ] || sleep 1
    done
    [ -n "$game_pid" ] || stop_check 'The eFootball process could not be found.'
    if [ "$(process_pid "$engine")" != "$bot_pid" ]; then
      record_exit "$package" "$bot_pid"
      stop_check 'The original bot process ended before the match window opened.'
    fi
    initial_score=$(score "$bot_pid" "$engine") || initial_score=unavailable
    printf 'Immediate reading before opening the window. Bot score %s, eFootball PID %s.\n' "$initial_score" "$game_pid"
  else
    check_phase 'Before' none
    base_low=$low; base_high=$high; baseline_input=$phase_input
  fi

  initial_display_setting=$previous
  previous=$(display_setting) || stop_check 'Android did not confirm the display setting.'
  if [ "$initial_display_setting" != unavailable ] && [ "$initial_display_setting" != "$previous" ]; then
    stop_check 'The display setting changed during setup.'
  fi
  case "$previous" in ''|null) ;; *) stop_check 'A simulated display is already configured.' ;; esac
  ids=$(overlay_ids) || stop_check 'Android did not list its displays.'
  [ -z "$ids" ] || stop_check 'A simulated display is already active.'
  [ ! -f "$work/cancel" ] || stop_check 'Check cancelled.'
  local current
  current=$(display_setting) || stop_check 'Android could not recheck the display setting.'
  [ "$current" = "$previous" ] || stop_check 'The display setting changed before the test.'
  printf '%s\n' "$previous" > "$work/previous" || stop_check 'Could not save the display setting.'
  write_display_setting put global "$key" "$panel" || stop_check 'Android could not enable the simulated display.'
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
  if [ "$mode" = match ]; then
    for attempt in 1 2 3 4 5; do
      [ ! -f "$work/cancel" ] || stop_check 'Check cancelled.'
      if check_windows "$display"; then
        record_match "$display"
        return $?
      fi
      [ "$attempt" -eq 5 ] || sleep 2
    done
    stop_check 'The window could not be verified on the second display.'
  fi
  check_phase 'With PeerLink visible' "$display"
  panel_low=$low; panel_high=$high; panel_input=$phase_input

  current=$(display_setting) || stop_check 'Android could not verify the temporary display setting.'
  [ "$current" = "$panel" ] || stop_check 'The simulated-display setting was changed separately during the check.'
  restore_display || stop_check 'Android did not confirm removal of the temporary display.'
  sleep 2
  open_game
  check_phase 'After removal' none
  printf '\nBot score ranges   before %s..%s   with window %s..%s   after removal %s..%s\n' \
    "$base_low" "$base_high" "$panel_low" "$panel_high" "$low" "$high"
  if [ "$baseline_input" != "$panel_input" ] || [ "$baseline_input" != "$phase_input" ]; then
    echo 'INCONCLUSIVE. Different windows held input during the three phases. The scores above remain available.'
  elif [ "$panel_high" -lt "$base_low" ] && [ "$panel_high" -lt "$low" ]; then
    echo 'RESULT. Android gave the same bot process stronger protection while PeerLink was visible in this waiting room.'
  elif [ "$base_low" = "$base_high" ] && [ "$panel_low" = "$panel_high" ] && \
       [ "$low" = "$high" ] && [ "$low" = "$panel_low" ] && [ "$low" = "$base_low" ]; then
    echo 'RESULT. The visible PeerLink window made no measured difference to the bot score.'
  elif [ "$panel_low" -gt "$base_high" ] && [ "$panel_low" -gt "$high" ]; then
    echo 'RESULT. The bot had weaker protection while PeerLink was visible.'
  else
    echo 'INCONCLUSIVE. The scores varied or the change did not reverse after the window closed.'
  fi
  if [ "$baseline_input" != "$game" ]; then
    printf 'Baseline measurements had input held by %s while eFootball remained the selected main-screen app. Game controls were only verified where input shows %s.\n' \
      "$baseline_input" "$game"
  fi
}

case "${1-}" in
  --measure) [ "$0" = "$work/run.sh" ] || exit 1; measure "$2" "$3" "$4"; exit $? ;;
  restore)
    [ -d "$work" ] || { echo 'No test display to restore.'; exit 0; }
    worker=$(cat "$work/pid" 2>/dev/null)
    if [ -n "$worker" ] && ps -p "$worker" -o ARGS= 2>/dev/null | grep -Fq "$work/run.sh --measure"; then
      touch "$work/cancel"; echo 'Cancellation requested. The running check will restore the display.'; exit 0
    fi
    release_check; exit $? ;;
  '') mode=compare ;;
  match) mode=match ;;
  *) echo 'Run without arguments for the priority check, with match to record one match, or with restore to stop.'; exit 1 ;;
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
# Exec a fresh shell so mksh closes the saved Prime Shell output descriptors.
# Only cat opens the Downloads file. Android services receive a pipe.
(
  trap '' HUP
  exec sh -c 'sh "$1" --measure "$2" "$3" "$4" 2>&1 | cat >> "$5"' \
    sh "$work/run.sh" "$android_user" "$bot_pid" "$mode" "$report"
) </dev/null >/dev/null 2>&1 &
if [ "$mode" = match ]; then
  printf 'Recording started. When the small PeerLink window appears in eFootball, join your room and play one match.\n'
else
  printf 'Check started. If eFootball stays in the background, open it from Home or Recents. Leave the title screen visible until the report finishes.\n'
fi
printf 'Downloads/%s\n' "${report##*/}"
