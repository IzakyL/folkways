#!/bin/sh

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)" || exit 1
cd "$ROOT" || exit 1

failed=0
summary=""
for task in test:e2e visual:take bench:take promo:take; do
  printf '\n[test:all] starting: %s\n' "$task"
  if npm run "$task"; then
    result="passed: $task"
  else
    status=$?
    result="failed: $task (exit $status)"
    failed=1
  fi
  summary="${summary}
${result}"
done

printf '\n[test:all] all runs finished:\n%s\n' "$summary"
exit "$failed"
