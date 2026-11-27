# Run once at the user's request, before opening a room.
counter() {
  dumpsys -t 3 activity settings | awk '
    $1 == "use_compaction=false" { off = 1 }
    /System Compaction Memory Stats/ { stats = 1; next }
    stats && $1 == "Compactions" && $2 == "Performed:" && $3 ~ /^[0-9]+$/ {
      count = $3; found = 1; stats = 0
    }
    END { if (off) print "off"; else if (found) print count; else print "unknown" }'
}
ram() {
  awk '$1 == "MemAvailable:" {print int(($2+512)/1024); found=1; exit} END {if (!found) exit 1}' /proc/meminfo
}

previous=$(counter)
if [ "$previous" = off ]; then
  echo 'Android has system compaction switched off on this phone.'
  exit 0
fi
before=$(ram) || exit 1
message=$(am compact system 2>&1) || { printf '%s\n' "$message"; exit 1; }
sleep 3
early=$(ram) || exit 1
sleep 7
after=$(ram) || exit 1
current=$(counter)

case "$previous/$current" in
  *unknown*|*off*) echo 'Android did not expose a counter to verify this request.' ;;
  *) if [ "$current" -gt "$previous" ]; then
       printf 'Android’s system compaction count rose from %s to %s.\n' "$previous" "$current"
     else
       echo 'Android recorded no new system compaction during this check.'
     fi ;;
esac
printf 'Available before  %s MiB\nAfter 3 seconds  %s MiB\nAfter 10 seconds  %s MiB (%+d MiB)\n' \
  "$before" "$early" "$after" "$((after - before))"
