#!/usr/bin/env bash
# Unattended runs: one fresh headless session per task, each following PROMPT.md.
# Each run starts at the brief's size instead of inheriting the previous task's context.
# Stops when: a STOP file exists, a run makes no commit, a run fails, or MAX runs are done.
#
#   MAX=5 scripts/claude-loop.sh
#   CLAUDE_FLAGS="--model sonnet --effort medium" scripts/claude-loop.sh
#
# Headless sessions can't answer permission prompts. Pre-allow the tools the project needs in
# .claude/settings.local.json ("permissions": {"allow": [...]}) or pass --allowedTools in CLAUDE_FLAGS.
set -euo pipefail
cd "$(dirname "$0")/.."

MAX=${MAX:-5}
LOGDIR=logs/claude-loop
mkdir -p "$LOGDIR"

for i in $(seq 1 "$MAX"); do
  if [ -f STOP ]; then echo "STOP file found, stopping."; break; fi
  before=$(git rev-parse HEAD)
  log="$LOGDIR/$(date +%Y%m%d-%H%M%S).log"
  echo "Run $i/$MAX -> $log"
  # shellcheck disable=SC2086
  if ! claude -p "Follow PROMPT.md." --permission-mode acceptEdits ${CLAUDE_FLAGS:-} >"$log" 2>&1; then
    echo "Run $i failed (see $log), stopping."; break
  fi
  if [ "$(git rev-parse HEAD)" = "$before" ]; then
    echo "Run $i made no commit (see $log), stopping."; break
  fi
  tail -n 15 "$log"
done
