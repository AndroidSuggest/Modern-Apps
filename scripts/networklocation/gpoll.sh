#!/usr/bin/env bash
# Heartbeat poller for the generator. Emits status every 120s, exits when GEN END appears.
LOG="$HOME/geocoder-scratch/gen.log"
OUT="$HOME/geocoder-scratch/geocoder.geodb"
for i in $(seq 1 60); do
  sleep 120
  echo "=== gpoll $i $(date -u) ==="
  # biggest java RSS (the test worker with the big heap)
  ps -eo pid,rss,pcpu,etime,comm --sort=-rss | grep -m3 java || echo "no java"
  free -h | awk 'NR==1||/Mem|Swap/'
  [ -f "$OUT" ] && echo "geodb: $(stat -c%s "$OUT") bytes"
  echo "--- tail gen.log ---"
  tail -n 6 "$LOG"
  if grep -q 'GEN END' "$LOG"; then
    echo "*** GEN DONE ***"
    tail -n 20 "$LOG"
    break
  fi
done
