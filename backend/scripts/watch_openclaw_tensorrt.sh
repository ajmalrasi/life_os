#!/usr/bin/env bash
# Detect an alive-but-stuck TensorRT Edge-LLM server with a real generation.
set -u -o pipefail

MODEL_SERVICE="${MODEL_SERVICE:-openclaw-tensorrt-edgellm.service}"
MODEL_BASE_URL="${MODEL_BASE_URL:-http://127.0.0.1:11434}"
FAILURE_LIMIT="${FAILURE_LIMIT:-2}"
PROBE_TIMEOUT_SECONDS="${PROBE_TIMEOUT_SECONDS:-20}"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/lifeos"
FAILURE_FILE="$STATE_DIR/openclaw-tensorrt-watchdog.failures"
LOCK_FILE="$STATE_DIR/openclaw-tensorrt-watchdog.lock"

mkdir -p "$STATE_DIR"
if ! (set -o noclobber; printf '%s\n' "$$" > "$LOCK_FILE") 2>/dev/null; then
  old_pid=$(cat "$LOCK_FILE" 2>/dev/null || true)
  [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null && exit 0
  rm -f "$LOCK_FILE"
  (set -o noclobber; printf '%s\n' "$$" > "$LOCK_FILE") 2>/dev/null || exit 0
fi
trap 'rm -f "$LOCK_FILE"' EXIT

log() { logger -t lifeos-tensorrt-watchdog -- "$*" 2>/dev/null || true; }
clear_failures() { rm -f "$FAILURE_FILE"; }

# Let systemd own ordinary crash recovery. This probe is for a process that is
# still active but cannot complete a tiny request.
systemctl --user is-active --quiet "$MODEL_SERVICE" || exit 0

payload='{"model":"openclaw","messages":[{"role":"user","content":"Reply exactly: LIFEOS_HEALTH_OK"}],"temperature":0,"max_tokens":8,"stream":false}'
response=$(curl -fsS --max-time "$PROBE_TIMEOUT_SECONDS" \
  -H 'Content-Type: application/json' -d "$payload" \
  "$MODEL_BASE_URL/v1/chat/completions" 2>/dev/null || true)

if printf '%s' "$response" | grep -q 'LIFEOS_HEALTH_OK'; then
  clear_failures
  exit 0
fi

failures=0
[ -r "$FAILURE_FILE" ] && read -r failures < "$FAILURE_FILE" || true
failures=$((failures + 1))
printf '%s\n' "$failures" > "$FAILURE_FILE"
log "generation health failure $failures/$FAILURE_LIMIT"
if [ "$failures" -ge "$FAILURE_LIMIT" ]; then
  clear_failures
  log "restarting $MODEL_SERVICE after repeated generation failures"
  systemctl --user restart "$MODEL_SERVICE"
fi
