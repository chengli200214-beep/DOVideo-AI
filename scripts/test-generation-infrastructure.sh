#!/usr/bin/env bash
# Linux/CI counterpart to test-generation-infrastructure.ps1. No paid provider is enabled.
set -euo pipefail

task_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for task_command in docker curl java openssl ffmpeg ffprobe; do
  command -v "$task_command" >/dev/null 2>&1 || {
    echo "Missing required command: $task_command" >&2
    exit 1
  }
done
docker info >/dev/null

task_project="dovideo-generation-it-$(openssl rand -hex 6)"
task_compose=(docker compose -p "$task_project" -f "$task_root/docker-compose.generation-it.yml")
task_started=false
cleanup() {
  local task_status=$?
  trap - EXIT
  if [[ "$task_started" == true ]]; then
    # This random project owns only disposable containers/volumes; local application data is never targeted.
    if ! "${task_compose[@]}" down --volumes --remove-orphans; then
      echo "Cleanup failed for isolated project $task_project" >&2
      if [[ "$task_status" == 0 ]]; then task_status=1; fi
    fi
  fi
  exit "$task_status"
}
trap cleanup EXIT

GENERATION_IT_DB_PASSWORD="$(openssl rand -hex 16)"
GENERATION_IT_REDIS_PASSWORD="$(openssl rand -hex 16)"
GENERATION_IT_MINIO_PASSWORD="$(openssl rand -hex 16)"
GENERATION_IT_FFMPEG_DIR="${GENERATION_IT_FFMPEG_DIR:-$(dirname "$(command -v ffmpeg)")}"
export GENERATION_IT_DB_PASSWORD GENERATION_IT_REDIS_PASSWORD GENERATION_IT_MINIO_PASSWORD GENERATION_IT_FFMPEG_DIR
[[ -x "$GENERATION_IT_FFMPEG_DIR/ffmpeg" && -x "$GENERATION_IT_FFMPEG_DIR/ffprobe" ]] || {
  echo "GENERATION_IT_FFMPEG_DIR must contain ffmpeg and ffprobe" >&2
  exit 1
}

# Never inherit a developer's model credentials or paid-call switches into the isolated acceptance process.
export GENERATION_PROVIDER=mock GENERATION_PAID_ENABLED=false GENERATION_RECOVERY_ENABLED=false
export SEEDANCE_RECOVERY_ENABLED=false STORYBOARD_PAID_ENABLED=false
unset GENERATION_API_KEY SEEDANCE_API_KEY STORYBOARD_API_KEY SILICONFLOW_API_KEY

echo "Starting isolated acceptance project: $task_project"
task_started=true
"${task_compose[@]}" up -d --build --wait --wait-timeout 180
local_port() {
  local task_address
  task_address="$("${task_compose[@]}" port "$1" "$2")"
  [[ "$task_address" =~ ^127\.0\.0\.1:([0-9]+)$ ]] || {
    echo "Cannot resolve isolated local port for $1" >&2
    return 1
  }
  printf '%s' "${BASH_REMATCH[1]}"
}
task_mysql_port="$(local_port mysql 3306)"
task_minio_port="$(local_port minio 9000)"
GENERATION_IT_REDIS_PORT="$(local_port redis 6379)"
export GENERATION_IT_REDIS_PORT
export GENERATION_IT_DB_URL="jdbc:mysql://127.0.0.1:$task_mysql_port/generation_it?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
export GENERATION_IT_MINIO_URL="http://127.0.0.1:$task_minio_port"
curl --fail --silent --show-error --retry 30 --retry-connrefused --retry-delay 1 --retry-max-time 60 \
  "$GENERATION_IT_MINIO_URL/minio/health/ready" >/dev/null

cd "$task_root/server"
./mvnw -B -Pgeneration-integration verify "$@"
