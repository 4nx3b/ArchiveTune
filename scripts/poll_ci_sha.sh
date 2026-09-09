#!/bin/bash
# Poll CI check-runs for a given head sha until all complete.
# Usage: poll_ci_sha.sh <sha> [max_minutes]
SHA=${1:?usage: poll_ci_sha.sh <sha> [max_minutes]}
MAX_MIN=${2:-30}
TOKEN=$(git -C /home/z/my-project config --get remote.origin.url | sed -n 's|https://[^:]*:\([^@]*\)@.*|\1|p')
REPO="4nx3b/ArchiveTune"
DEADLINE=$(( $(date +%s) + MAX_MIN * 60 ))

while true; do
  OUT=$(curl -s -H "Authorization: Bearer $TOKEN" \
    "https://api.github.com/repos/$REPO/commits/$SHA/check-runs" | python3 -c "
import json, sys
d = json.load(sys.stdin)
runs = d.get('check_runs', [])
if not runs:
    print('NORUNS')
    sys.exit(0)
lines = []
for c in runs:
    lines.append(f\"{c['status']:<12} {str(c.get('conclusion')):<10} {c['name']}\")
done = all(c['status'] == 'completed' for c in runs)
failed = [c['name'] for c in runs if c.get('conclusion') not in (None, 'success', 'skipped', 'neutral')]
print('DONE' if done and not failed else ('FAILED' if done else 'RUNNING'))
print(f\"{len(runs)} check-runs, {sum(1 for c in runs if c['status']=='completed')} completed\")
for l in lines:
    print(l)
")
  STATUS=$(echo "$OUT" | head -1)
  echo "=== $(date +%H:%M:%S) ==="
  echo "$OUT"
  if [ "$STATUS" = "DONE" ] || [ "$STATUS" = "FAILED" ]; then
    exit 0
  fi
  if [ "$(date +%s)" -gt "$DEADLINE" ]; then
    echo "TIMEOUT after ${MAX_MIN} min — still not complete"
    exit 1
  fi
  sleep 60
done
