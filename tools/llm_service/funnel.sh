#!/usr/bin/env bash
# День 30 / вариант: публичный сервис через Tailscale Funnel.
# Сервер — Mac с Tailscale; клиентам ничего ставить не нужно (обычный HTTPS-URL).
# Никаких автостартов: up — подняли, down — погасили.
#
#   tools/llm_service/funnel.sh up      # шлюз (127.0.0.1:8080) + tailscale funnel, печатает URL и ключ
#   tools/llm_service/funnel.sh down    # снять funnel + остановить шлюз
#   tools/llm_service/funnel.sh status
#
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${PY:-$DIR/../../.venv/bin/python}"
TS="${TS:-/Applications/Tailscale.app/Contents/MacOS/Tailscale}"
RUN="/tmp/llm_public"
GATEWAY_LOG="$RUN/gateway.log"
KEY_FILE="$RUN/gateway.key"
GW_PID="$RUN/gateway.pid"
PORT="${GATEWAY_PORT:-8080}"
MODEL="${DEFAULT_MODEL:-gpt-oss-32k}"
mkdir -p "$RUN"

gateway_alive() { [ -f "$GW_PID" ] && kill -0 "$(cat "$GW_PID")" 2>/dev/null; }
port_listening() { lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; }

public_url() {
  "$TS" funnel status 2>/dev/null | grep -Eo 'https://[a-z0-9.-]+\.ts\.net' | head -1 || true
}

cmd_up() {
  [ -f "$KEY_FILE" ] || { openssl rand -hex 32 > "$KEY_FILE"; }
  chmod 600 "$KEY_FILE"
  local key; key="$(cat "$KEY_FILE")"

  if port_listening; then
    echo "gateway уже слушает :$PORT"
  else
    GATEWAY_HOST=127.0.0.1 GATEWAY_PORT="$PORT" GATEWAY_API_KEY="$key" \
      RATE_LIMIT_PER_MIN="${RATE_LIMIT_PER_MIN:-30}" \
      MAX_CONTEXT_TOKENS="${MAX_CONTEXT_TOKENS:-8192}" \
      MAX_OUTPUT_TOKENS="${MAX_OUTPUT_TOKENS:-1024}" \
      DEFAULT_MODEL="$MODEL" \
      nohup "$PY" "$DIR/gateway.py" > "$GATEWAY_LOG" 2>&1 &
    echo $! > "$GW_PID"
  fi
  for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$PORT/health" && break; sleep 0.5; done
  curl -s -o /dev/null "http://127.0.0.1:$PORT/health" || { echo "шлюз не поднялся: $GATEWAY_LOG"; exit 1; }

  "$TS" funnel --bg "$PORT" >/dev/null 2>&1 || "$TS" funnel "$PORT"
  sleep 1
  local url; url="$(public_url)"
  echo
  echo "=== Публичный AI-сервис (Tailscale Funnel) ==="
  echo "URL:  $url"
  echo "KEY:  $key"
  echo "Модель: $MODEL · лимит: ${RATE_LIMIT_PER_MIN:-30}/мин"
  echo
  echo "Проверка с любого устройства (без установки):"
  echo "  curl $url/v1/chat/completions -H 'Authorization: Bearer $key' \\"
  echo "       -H 'Content-Type: application/json' -d '{\"messages\":[{\"role\":\"user\",\"content\":\"привет\"}]}'"
}

cmd_down() {
  "$TS" funnel --https=443 off 2>/dev/null || "$TS" funnel off 2>/dev/null || true
  echo "funnel снят"
  if gateway_alive; then kill "$(cat "$GW_PID")" 2>/dev/null || true; fi
  # добить всё, что реально слушает порт (на случай устаревшего PID-файла)
  lsof -nP -iTCP:"$PORT" -sTCP:LISTEN -t 2>/dev/null | xargs kill 2>/dev/null || true
  rm -f "$GW_PID"
  echo "gateway остановлен"
}

cmd_status() {
  port_listening && echo "gateway: работает (порт $PORT)" || echo "gateway: остановлен"
  local url; url="$(public_url)"; [ -n "$url" ] && echo "funnel: $url" || echo "funnel: выключен"
}

case "${1:-}" in
  up) cmd_up ;;
  down) cmd_down ;;
  status) cmd_status ;;
  *) echo "usage: $0 {up|down|status}"; exit 2 ;;
esac
