#!/bin/bash
# Poll CI status for a given head sha until all runs complete.
SHA=${1:-c3c61227c}
TOKEN=$(cd /home/z/my-project/ArchiveTune && git remote get-url origin | sed -E 's|https://([^:]+):([^@]+)@.*|\2|')
REPO="4nx3b/ArchiveTune"

for i in $(seq 1 60); do
  OUT=$(curl -s -H "Authorization: Bearer $TOKEN" "https://api.github.com/repos/$REPO/actions/runs?head_sha=$SHA&per_page=10" | python3 -c "
import json,sys
data = json.load(sys.stdin)
runs = data.get('workflow_runs', [])
if not runs:
    print('NORUNS')
else:
    done_count = sum(1 for r in runs if r['status'] == 'completed')
    lines = []
    all_done = True
    for r in runs:
        lines.append(f\"{r['name']} -> {r['status']} {r.get('conclusion')}\")
        if r['status'] != 'completed':
            all_done = False
    print(('ALLDONE' if all_done else 'RUNNING') + ' (' + str(done_count) + '/' + str(len(runs)) + ')')
    for l in lines: print(l)
")
  echo "=== poll $i ==="
  echo "$OUT"
  if echo "$OUT" | grep -q "^ALLDONE"; then
    if echo "$OUT" | grep -q "failure\|cancelled\|timed_out"; then
      echo "RESULT: FAILURE"
      exit 1
    fi
    echo "RESULT: SUCCESS"
    exit 0
  fi
  if [ "$OUT" = "NORUNS" ]; then
    echo "no runs yet"
  fi
  sleep 60
done
echo "RESULT: TIMEOUT"
exit 2
