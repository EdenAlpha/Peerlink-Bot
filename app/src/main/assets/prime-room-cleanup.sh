case "${1:-prepare}" in
  guard)
    # Android rechecks cached process priority when it executes kill-all.
    # Only request it while the game and PeerLink both have active UIDs.
    for uid in "${2:-}" "${3:-}"; do
      case "$uid" in ''|*[!0-9]*) exit 2 ;; esac
      state=$(am get-uid-state "$uid") || exit $?
      case "${state%% *}" in
        0|1|2|3|4|5|6) ;;
        *) printf 'Cached sweep skipped for UID %s in state %s\n' "$uid" "$state"; exit 0 ;;
      esac
    done
    am kill-all || exit $?
    echo 'Android cached-app sweep requested'
    exit 0
    ;;
  prepare) ;;
  *) exit 2 ;;
esac

ram() { awk '/^MemAvailable:/ {printf "%.0f\n", $2/1024}' /proc/meminfo; }
user_id=$(am get-current-user) || exit $?
live_uids=$(ps -A -n -o UID,RSS) || exit $?
downloaded=$(pm list packages -3 --user "$user_id") || exit $?
installed=$(pm list packages -U --user "$user_id") || exit $?

resolve() { cmd package resolve-activity --components --user "$user_id" "$@"; }
home=$(resolve -a android.intent.action.MAIN -c android.intent.category.HOME) || exit $?
dialer=$(resolve -a android.intent.action.DIAL -d tel:) || exit $?
sms=$(resolve -a android.intent.action.SENDTO -d smsto:) || exit $?
keyboard=$(settings --user "$user_id" get secure default_input_method) || exit $?
accessibility=$(settings --user "$user_id" get secure enabled_accessibility_services) || exit $?
always_on_vpn=$(settings --user "$user_id" get secure always_on_vpn_app) || exit $?
vpn_services=$(cmd package query-services --components --user "$user_id" -a android.net.VpnService) || exit $?
protected=$(
  printf '%s\n' com.peerlink.app jp.konami.pesam moe.shizuku.privileged.api \
    com.android.vending com.google.android.gms com.google.android.play.games
  printf '%s\n' "$home" "$dialer" "$sms" "$keyboard" "$accessibility" "$always_on_vpn/" "$vpn_services" |
    tr ':' '\n' | awk -F/ 'NF > 1 { sub(/^[[:space:]]+/, "", $1); print $1 }'
)

quiet='com.instagram.android com.openai.chatgpt com.twitter.android com.google.android.youtube'
optional='com.snapchat.android com.whatsapp com.whatsapp.w4b com.zhiliaoapp.musically com.ss.android.ugc.trill com.facebook.katana com.facebook.orca com.facebook.lite com.google.android.apps.photos com.google.android.apps.maps com.google.android.gm com.google.android.apps.docs com.google.android.apps.youtube.music com.transsion.aivoiceassistant'

targets=$(
  printf '%s\n' "$live_uids" '#downloaded' "$downloaded" '#keep' "$protected" '#installed' "$installed" |
  awk -v optional="$optional" -v quiet="$quiet" '
    BEGIN {
      n = split(optional, names, " ")
      for (i = 1; i <= n; i++) optional_app[names[i]] = 1
      n = split(quiet, names, " ")
      for (i = 1; i <= n; i++) always_stop[names[i]] = 1
    }
    /^#/ { section = $0; next }
    section == "" && $1 ~ /^[0-9]+$/ && $2 ~ /^[0-9]+$/ { live[$1] += $2; next }
    section == "#downloaded" && /^package:/ { downloaded[substr($1, 9)] = 1; next }
    section == "#keep" && NF == 1 { keep[$1] = 1; next }
    section == "#installed" && /^package:/ && $2 ~ /^uid:[0-9]+$/ {
      uid[substr($1, 9)] = substr($2, 5)
    }
    END {
      for (pkg in keep) {
        if (pkg in uid) protected_uid[uid[pkg]] = 1
      }
      for (pkg in uid) {
        if ((pkg in keep) || (uid[pkg] in protected_uid)) continue
        if ((pkg in always_stop) ||
            ((uid[pkg] in live) && ((pkg in downloaded) || (pkg in optional_app)))) print pkg
      }
    }
  '
) || exit $?

before=$(ram) || exit $?
printf 'Before %s MiB available\n' "$before"
result=0
for app in $targets; do
  printf 'Force-stop %s\n' "$app"
  am force-stop --user "$user_id" "$app" || result=1
done
printf 'After force-stops %s MiB available\n' "$(ram)"
am kill-all || result=1
sleep 2
after=$(ram) || exit $?
printf 'Final %s MiB available (%+d MiB)\n' "$after" "$((after - before))"

# Capture the system while the reclaimed apps are still stopped and before the bot loads.
printf 'Post-cleanup memory from /proc/meminfo (categories overlap)\n'
awk '/^(MemTotal|MemFree|MemAvailable|Cached|Shmem|SReclaimable|SUnreclaim|SwapTotal|SwapFree):/ {print}' /proc/meminfo
printf 'Largest remaining processes by RSS in KiB (includes shared pages)\n'
ps -A -n -o UID,PID,RSS,NAME | awk '$2 ~ /^[0-9]+$/ && $3 ~ /^[0-9]+$/ {print $3, $4, "pid", $2, "uid", $1}' |
  sort -nr | head -15

if printf '%s\n' "$targets" | grep -Fxq com.google.android.youtube; then
  dumpsys -t 5 package com.google.android.youtube | awk -v user="$user_id" '
    $0 == "Packages:" { installed = 1; next }
    $0 == "Hidden system packages:" { installed = 0 }
    installed && !reported && $1 == "User" && $2 == (user ":") {
      for (i = 3; i <= NF; i++) {
        if ($i ~ /^stopped=/) {
          print "YouTube " $i
          reported = 1
          break
        }
      }
    }
  '
fi
exit "$result"
