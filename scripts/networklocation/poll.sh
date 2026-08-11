#!/usr/bin/env bash
# Heartbeat poller for the planet extraction. Emits status every 120s so the
# outer tool call does not hit its idle timeout, and exits when extraction ends.
LOG="$HOME/geocoder-scratch/extract.log"
OUT="$HOME/geocoder-scratch/addr.geojsonseq"
for i in $(seq 1 30); do
  sleep 120
  echo "=== poll $i $(date -u) ==="
  if pgrep osmium >/dev/null; then
    ps -o pid,etime,pcpu,cmd -p "$(pgrep osmium)" | tail -n +2
  else
    echo "osmium NOT running"
  fi
  for f in /tmp/tmp.*/addr.osm.pbf; do
    [ -f "$f" ] && echo "filtered_pbf: $(stat -c%s "$f") bytes"
  done
  [ -f "$OUT" ] && echo "geojsonseq: $(stat -c%s "$OUT") bytes"
  tail -2 "$LOG"
  if grep -q '^END' "$LOG"; then
    echo "*** EXTRACT DONE ***"
    tail -5 "$LOG"
    break
  fi
done
