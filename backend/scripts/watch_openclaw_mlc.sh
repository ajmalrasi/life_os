#!/usr/bin/env bash
# Detect the MLC failure mode where the HTTP process stays alive after its
# decoding thread dies. A /v1/models-only health check cannot see this.
set -uo pipefail

MODEL_CONTAINER="${MODEL_CONTAINER:-openclaw-mlc}"
MODEL_SERVICE="${MODEL_SERVICE:-openclaw-mlc.service}"
MODEL_BASE_URL="${MODEL_BASE_URL:-http://localhost:11434}"
MIN_UPTIME_SECONDS="${MIN_UPTIME_SECONDS:-300}"
QUIET_SECONDS="${QUIET_SECONDS:-180}"
FAILURE_LIMIT="${FAILURE_LIMIT:-2}"
PROBE_TIMEOUT_SECONDS="${PROBE_TIMEOUT_SECONDS:-45}"
STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/lifeos"
FAILURE_FILE="$STATE_DIR/openclaw-mlc-watchdog.failures"
LOCK_FILE="$STATE_DIR/openclaw-mlc-watchdog.lock"
DRY_RUN="${MODEL_WATCHDOG_DRY_RUN:-0}"
ANCILLARY_CONTAINERS="${ANCILLARY_CONTAINERS:-rabbit_hole_api rabbit_hole_db}"
ANCILLARY_USER_SERVICE="${ANCILLARY_USER_SERVICE:-openclaw-gateway.service}"

mkdir -p "$STATE_DIR"
if ! (set -o noclobber; printf '%s\n' "$$" > "$LOCK_FILE") 2>/dev/null; then
  old_pid=$(cat "$LOCK_FILE" 2>/dev/null || true)
  if [ -n "$old_pid" ] && kill -0 "$old_pid" 2>/dev/null; then
    exit 0
  fi
  rm -f "$LOCK_FILE"
  (set -o noclobber; printf '%s\n' "$$" > "$LOCK_FILE") 2>/dev/null || exit 0
fi
trap 'rm -f "$LOCK_FILE"' EXIT

log() {
  logger -t lifeos-model-watchdog -- "$*" 2>/dev/null || printf '%s\n' "$*"
}

clear_failures() {
  rm -f "$FAILURE_FILE"
}

recover_model() {
  local reason="$1"
  local ancillary_service_was_active=0
  local container
  local ready=0
  local -a running_containers=()
  clear_failures
  if [ "$DRY_RUN" = "1" ]; then
    log "DRY RUN: would restart $MODEL_SERVICE ($reason)"
    return 0
  fi

  # A 4K KV cache fits once resident, but loading its weights can fail on the
  # Jetson when other services fragment unified CUDA memory. Temporarily free
  # the known ancillary consumers, let the model allocate first, then restore
  # exactly what was running before recovery.
  if systemctl --user is-active --quiet "$ANCILLARY_USER_SERVICE"; then
    ancillary_service_was_active=1
    systemctl --user stop "$ANCILLARY_USER_SERVICE"
  fi
  for container in $ANCILLARY_CONTAINERS; do
    if [ "$(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null)" = "true" ]; then
      running_containers+=("$container")
      docker stop "$container" >/dev/null
    fi
  done

  log "restarting 4K $MODEL_SERVICE ($reason)"
  # The systemd wrapper removes the --rm container, waits for CUDA memory to
  # settle, drops page cache, and proves a real generation before serving.
  systemctl --user restart "$MODEL_SERVICE"
  for _ in $(seq 1 60); do
    if curl -fsS --max-time 2 "$MODEL_BASE_URL/v1/models" >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 3
  done

  if [ "$ancillary_service_was_active" = "1" ]; then
    systemctl --user start "$ANCILLARY_USER_SERVICE"
  fi
  if [ "${#running_containers[@]}" -gt 0 ]; then
    docker start "${running_containers[@]}" >/dev/null
  fi
  if [ "$ready" = "1" ]; then
    log "4K model HTTP ready; ancillary services restored"
  else
    log "4K model did not become ready within 180 seconds; ancillary services restored"
    return 1
  fi
}

record_failure() {
  local reason="$1"
  local failures=0
  if [ -r "$FAILURE_FILE" ]; then
    read -r failures < "$FAILURE_FILE" || failures=0
  fi
  failures=$((failures + 1))
  printf '%s\n' "$failures" > "$FAILURE_FILE"
  log "generation health failure $failures/$FAILURE_LIMIT ($reason)"
  if [ "$failures" -ge "$FAILURE_LIMIT" ]; then
    recover_model "$reason repeated $failures times"
  fi
}

if [ "${MODEL_WATCHDOG_FORCE_FATAL:-0}" = "1" ]; then
  recover_model "forced fatal-path test"
  exit 0
fi

# The model's existing systemd wrapper already owns startup retries and its
# 4096 -> 2048 context fallback. Do not interfere while that is in progress.
systemctl --user is-active --quiet "$MODEL_SERVICE" || exit 0
started_at=$(docker inspect -f '{{.State.StartedAt}}' "$MODEL_CONTAINER" 2>/dev/null) || exit 0
started_epoch=$(date -d "$started_at" +%s 2>/dev/null) || exit 0
now_epoch=$(date +%s)
uptime_seconds=$((now_epoch - started_epoch))
if [ "$uptime_seconds" -lt "$MIN_UPTIME_SECONDS" ]; then
  exit 0
fi

# This exact background-thread failure leaves Uvicorn and /v1/models alive but
# makes every generation hang forever. It is safe to recover immediately.
container_logs=$(docker logs --since "$started_at" "$MODEL_CONTAINER" 2>&1 || true)
if printf '%s' "$container_logs" | grep -Eq \
  'Exception in thread Thread-1|There should be at least one request state entry that can run decode'; then
  recover_model "MLC decoding thread crashed"
  exit 0
fi

# Avoid injecting a health request while a real Ask generation may be active.
# The next timer run will probe after the service has been quiet long enough.
if docker logs --since "${QUIET_SECONDS}s" "$MODEL_CONTAINER" 2>&1 \
  | grep -q 'POST /v1/chat/completions'; then
  clear_failures
  exit 0
fi

if ! curl -fsS --max-time 5 "$MODEL_BASE_URL/v1/models" >/dev/null; then
  record_failure "/v1/models unavailable"
  exit 0
fi

probe_payload='{"model":"openclaw","messages":[{"role":"user","content":"Reply only: OK"}],"max_tokens":1,"stream":false}'
if curl -fsS --max-time "$PROBE_TIMEOUT_SECONDS" \
  -H 'Content-Type: application/json' \
  -d "$probe_payload" "$MODEL_BASE_URL/v1/chat/completions" >/dev/null; then
  clear_failures
  exit 0
fi

record_failure "one-token generation timed out or failed"
