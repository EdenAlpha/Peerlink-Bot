#!/bin/sh
action=${0##*/}
phase=$(cat "$FIX/phase" 2>/dev/null || echo before)
if [ "$CASE" = binder_fds ]; then
  case "$action" in settings|am|cmd|dumpsys)
    if [ -f /proc/self/fd/1 ] || [ -f /proc/self/fd/2 ]; then
      echo "cmd: Failure calling service $action: Failed transaction (2147483646)"
      exit 1
    fi ;;
  esac
fi
if [ "$action" = cmd ] && [ "$1" = settings ]; then
  shift
  action=settings
fi
case "$action" in
sleep)
  case "$CASE" in
    offscreen_*)
      read -r tick ignored < "$FIX/uptime"
      printf '%s.00 0.00\n' "$((${tick%%.*} + $1))" > "$FIX/uptime"
      /bin/sleep 0.025 ;;
  esac
  if [ "$CASE:$1" = manual_open:3 ] && [ -f "$FIX/launch_failed" ]; then touch "$FIX/game_front"; fi
  case "$CASE:$1" in
    match_bot_exit:10|offscreen_match_bot_exit:10) touch "$FIX/bot-exited" ;;
    match_game_exit:10) touch "$FIX/game-exited" ;;
    match_cancel:10)
      touch "$FIX/recording"
      for attempt in 1 2 3 4 5 6 7 8 9 10; do
        [ ! -f "$FIX/lock/cancel" ] || break
        /bin/sleep 0.1
      done ;;
  esac
  if [ "$CASE" = focus_interrupted ] || [ "$CASE" = screen_off ]; then
    seconds=$(cat "$FIX/seconds" 2>/dev/null || echo 0)
    echo "$((seconds + $1))" > "$FIX/seconds"
  fi
  exit 0 ;;
am)
  if [ "$1" = get-current-user ]; then echo 0; exit 0; fi
  printf '%s\n' "$*" >> "$FIX/am_calls"
  case "$*" in
    'task focus 76') touch "$FIX/game_front"; exit 0 ;;
    'task focus '*) exit 1 ;;
    *--display\ 52*)
      [ -f "$FIX/virtual-display" ] || exit 1
      echo 'virtual' > "$FIX/phase"
      exit 0 ;;
    *--display\ 37*) [ "$CASE" != launch_denied ] || exit 1
      if [ "$CASE" = resume_task ]; then rm -f "$FIX/game_front"; fi; exit 0 ;;
    *'-p jp.konami.pesam'*)
      echo 'Error: Activity not started, unable to resolve Intent { act=android.intent.action.MAIN cat=[android.intent.category.LAUNCHER] flg=0x10000000 pkg=jp.konami.pesam }'
      exit 1 ;;
    *'-n jp.konami.pesam/'*)
      comp=${*##* -n }
      case "$CASE:$comp" in
        manual_open:*|never_open:*) touch "$FIX/launch_failed"; echo 'Error: launcher unavailable'; exit 1 ;;
        dynamic_launcher:jp.konami.pesam/.NewLauncher|*:jp.konami.pesam/com.epicgames.ue4.SplashActivity)
          touch "$FIX/game_front"; exit 0 ;;
        *) echo 'Error type 3'; exit 1 ;;
      esac ;;
    *) exit 1 ;;
  esac
  ;;
settings)
  case "$1" in
    get)
      case "$CASE" in
        wrapper_failure|list_has_value|list_empty_value|both_reads_denied|invalid_list|list_has_no_output)
          echo 'cmd: diagnostic settings get unavailable'; exit 7 ;;
        error_as_success) echo 'cmd: malformed response'; exit 0 ;;
      esac
      cat "$FIX/setting";;
    list)
      case "$CASE" in
        both_reads_denied) echo 'cmd: Permission denied reading settings' >&2; exit 13 ;;
        invalid_list) echo 'vendor_private_setting=DO_NOT_EXPORT'; exit 0 ;;
        list_has_no_output) exit 0 ;;
      esac
      echo 'device_provisioned=1'
      echo 'vendor_private_setting=DO_NOT_EXPORT'
      old=$(cat "$FIX/setting")
      if [ "$old" != null ]; then printf 'overlay_display_devices=%s\n' "$old"; fi ;;
    put)
      touch "$FIX/setting-writes"
      if [ "$CASE" = put_denied ]; then echo 'vendor refused write' >&2; exit 12; fi
      if [ -z "$4" ]; then echo after > "$FIX/phase"
      else echo panel > "$FIX/phase"; fi
      printf '%s\n' "$4" > "$FIX/setting";;
    delete)
      touch "$FIX/setting-writes"
      if [ "$CASE" = restore_denied ]; then echo 'vendor refused restore'; exit 1; fi
      echo after > "$FIX/phase"; echo null > "$FIX/setting";;
  esac ;;
cmd)
  if [ "$1 $2" = 'package resolve-activity' ]; then
    case "$CASE" in
      dynamic_launcher) echo 'priority=0 match=0x108000'; echo 'jp.konami.pesam/.NewLauncher' ;;
      resolver_other) echo 'android/com.android.internal.app.ResolverActivity' ;;
      resolver_stale) echo 'jp.konami.pesam/.Removed' ;;
      *) echo 'No activity found' ;;
    esac
    exit 0
  fi
  echo 'Displays:'
  echo 'Display id 0: DisplayInfo{"Built-in Screen", displayId 0, type INTERNAL, uniqueId "local:0"}, isValid=true'
  echo 'Display id 5: DisplayInfo{"Projector", displayId 5, type EXTERNAL, uniqueId "local:5"}, isValid=true'
  if [ "$phase" = panel ] && [ "$CASE" != no_display ]; then
    echo 'Display id 37: DisplayInfo{"Écran simulé", displayId 37, type OVERLAY, uniqueId "overlay:1"}, isValid=true'
  fi
  if [ -f "$FIX/virtual-display" ]; then
    echo 'Display id 52: DisplayInfo{"PeerLink off-screen", displayId 52, type VIRTUAL, uniqueId "virtual:test"}, isValid=true'
  fi ;;
app_process)
  [ "$1" = /system/bin ] && [ "$2" = PeerLinkOffscreenDisplay ] || exit 1
  [ "$(stat -c %a "$CLASSPATH")" = 444 ] || exit 1
  lifetime=180
  [ "$CASE" != offscreen_match_bot_exit ] || lifetime=1020
  [ "$4" = "$lifetime" ] && [ "$5" = "$(cat "$3/pid")" ] || exit 1
  if [ "$CASE" = offscreen_denied ]; then
    echo 'SecurityException: Requires ADD_TRUSTED_DISPLAY permission to create a trusted virtual display.'
    exit 1
  fi
  directory=$3
  touch "$FIX/virtual-display"
  echo 52 > "$directory/display-id"
  while [ -d "$directory" ] && [ ! -f "$directory/display-stop" ]; do /bin/sleep 0.01; done
  rm -f "$FIX/virtual-display"
  echo after > "$FIX/phase" ;;
ps)
  if [ "$1" = -p ]; then exec /bin/ps -p "$2" -o args=; fi
  [ "$1" = -A ] || exit 0
  echo 'UID PID NAME'
  echo '10541 800001 com.peerlink.app'
  echo '1010541 800018 com.peerlink.app:botengine'
  p=800002; [ "$phase:$CASE" != panel:bot_restarted ] || p=810002
  if [ "$CASE" != no_bot ] && [ ! -f "$FIX/bot-exited" ]; then
    echo "10541 $p com.peerlink.app:botengine"
  fi
  g=800003; [ "$phase:$CASE" != panel:game_restarted ] || g=810003
  if [ ! -f "$FIX/game-exited" ]; then
    echo "10642 $g jp.konami.pesam"
  fi ;;
dumpsys)
  shift 2
  case "$1 $2" in
    'activity activities')
      echo 'Display #8 (activities from top to bottom):'
      echo '    * Hist  #0: ActivityRecord{abc u0 jp.konami.pesam/com.epicgames.ue4.GameActivity t654}'
      echo 'Display #0 (activities from top to bottom):'
      echo '    * Hist  #0: ActivityRecord{def u10 jp.konami.pesam/com.epicgames.ue4.GameActivity t321}'
      echo '    * Hist  #0: ActivityRecord{def u0 jp.konami.pesam.other/.Activity t322}'
      if [ "$CASE" = resume_task ]; then
        echo '    * Hist  #0: ActivityRecord{abc u0 jp.konami.pesam/com.epicgames.ue4.GameActivity t76}'
      fi ;;
    'activity settings') echo '  top_to_fgs_grace_duration=15000'; echo '  top_to_almost_perceptible_grace_duration=15000' ;;
    'activity exit-info')
      case "$3" in
        com.peerlink.app) pid=800002; process=com.peerlink.app:botengine ;;
        jp.konami.pesam) pid=800003; process=jp.konami.pesam ;;
        *) exit 1 ;;
      esac
      echo '  ApplicationExitInfo #0:'
      echo "    timestamp=2026-11-28 07:37:01.662 pid=$pid user=0"
      echo "    process=$process reason=3 (LOW_MEMORY)"
      echo '  ApplicationExitInfo #1:'
      echo '    timestamp=2026-11-27 22:15:03.320 pid=17099 user=0' ;;
    'activity processes')
      case "$3" in
        com.peerlink.app)
          score=200
          if [ "$phase" = panel ]; then
            case "$CASE" in unchanged) ;; weaker) score=300 ;; *) score=100 ;; esac
          elif [ "$phase:$CASE" = after:no_reversal ]; then score=100; fi
          if [ "$CASE" = offscreen_ready ]; then
            case "$phase" in before) score=100 ;; virtual) score=50 ;; esac
          fi
          echo '  *APP* UID 10541 ProcessRecord{34b1 800001:com.peerlink.app/u0a541}'
          echo '    oom adj: max=1001 curRaw=35 setRaw=35 cur=35 set=35'
          echo '  *APP* UID 10541 ProcessRecord{4ca 800002:com.peerlink.app:botengine/u0a541}'
          [ "$CASE" = no_score ] || echo "    oom adj: max=1001 curRaw=12 setRaw=15 cur=25 set=$score"
          echo '  *APP* UID 10541 ProcessRecord{bbc 800036:com.peerlink.app:other/u0a541}'
          echo '    oom adj: max=1001 curRaw=15 setRaw=15 cur=25 set=25' ;;
        jp.konami.pesam)
          echo '  *APP* UID 10642 ProcessRecord{912 800003:jp.konami.pesam/u0a642}'
          echo '    oom adj: max=1001 curRaw=0 setRaw=0 cur=0 set=0' ;;
      esac ;;
    'window visible-apps')
      echo 'WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)'
      echo '  Display: mDisplayId=0 (organized)'
      input='Window{8 u0 jp.konami.pesam/com.epicgames.ue4.GameActivity}'
      foreground='ActivityRecord{8 u0 jp.konami.pesam/com.epicgames.ue4.GameActivity t76}'
      screen=true
      case "$CASE" in
        dynamic_launcher|stock_launcher|resolver_other|resolver_stale|manual_open|resume_task|never_open)
          if [ ! -f "$FIX/game_front" ]; then
            input='Window{8e u0 com.android.settings/.Settings}'
            foreground='ActivityRecord{8e u0 com.android.settings/.Settings t23}'
          fi ;;
        system_input) input='Window{8f u0 NotificationShade}' ;;
        oem_window_title) input='Window{8 u0 CustomRenderer}' ;;
        match_bot_exit)
          if [ "$phase" = before ]; then
            calls=$(cat "$FIX/focus_calls" 2>/dev/null || echo 0)
            echo "$((calls + 1))" > "$FIX/focus_calls"
            if [ "$((calls % 2))" -eq 1 ]; then input='Window{8f u0 NotificationShade}'; fi
          fi ;;
        focus_interrupted|screen_off)
          seconds=$(cat "$FIX/seconds" 2>/dev/null || echo 0)
          if [ "$seconds" -ge 20 ] && [ "$phase" = before ]; then
            if [ "$CASE" = screen_off ]; then input=null; screen=false
            elif [ "$seconds" -lt 24 ]; then input=null; fi
          fi ;;
      esac
      case "$phase:$CASE" in
        panel:wrong_app)
          input='Window{8e u0 com.android.settings/.Settings}'
          foreground='ActivityRecord{8e u0 com.android.settings/.Settings t23}' ;;
        panel:different_input) input='Window{8f u0 NotificationShade}' ;;
      esac
      printf '  mCurrentFocus=%s\n  mFocusedApp=%s\n' "$input" "$foreground"
      printf '  mAwake=%s mScreenOnEarly=%s mScreenOnFully=%s\n' "$screen" "$screen" "$screen"
      [ "$phase" != panel ] || echo '  Display: mDisplayId=37 (organized)'
      echo 'WINDOW MANAGER WINDOWS (dumpsys window windows)'
      if [ "$CASE" = oem_window_title ]; then
        echo '  Window #0 Window{8 u0 CustomRenderer}:'
      else
        echo '  Window #0 Window{8 u0 jp.konami.pesam/com.epicgames.GameActivity}:'
      fi
      echo '    mDisplayId=0 rootTaskId=44'
      echo '    mOwnerUid=10642 showForAllUsers=false package=jp.konami.pesam appop=NONE'
      if [ "$phase" = panel ] && [ "$CASE" != no_window ] && [ "$CASE" != match_no_window ]; then
        if [ "$CASE" = oem_window_title ]; then echo '  Window #1 Window{1 u0 PeerLink}:'
        else echo '  Window #1 Window{1 u0 com.peerlink.app/com.peerlink.app.ui.MainActivity}:'; fi
        if [ "$CASE" = wrong_display ]; then echo '    mDisplayId=0 rootTaskId=56'
        else echo '    mDisplayId=37 rootTaskId=56'; fi
        echo '    mActivityRecord=ActivityRecord{18 u0 com.peerlink.app/.ui.MainActivity t56}'
      fi
      if [ "$phase" = virtual ] && [ -f "$FIX/virtual-display" ]; then
        echo '  Window #1 Window{1 u0 com.peerlink.app/com.peerlink.app.ui.MainActivity}:'
        echo '    mDisplayId=52 rootTaskId=56'
        echo '    mActivityRecord=ActivityRecord{18 u0 com.peerlink.app/.ui.MainActivity t56}'
      fi
      if [ "$phase:$CASE" = panel:wrong_focus ]; then echo '  mTopFocusedDisplayId=37'; else echo '  mTopFocusedDisplayId=0'; fi
      if [ "$phase:$CASE" = panel:user_changed ]; then echo '800x600/160' > "$FIX/setting"; fi ;;
  esac ;;
esac
