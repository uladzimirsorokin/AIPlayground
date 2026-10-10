#!/usr/bin/env bash
# День 30 / вариант C-lite: быстро поднять приватный LLM-сервис через Cloudflare quick-tunnel.
# Без домена (URL вида https://<random>.trycloudflare.com), без Access — защита только gateway-ключом.
# Никаких автостартов: up — подняли, down — погасили.
#
#   tools/llm_service/public.sh up      # запустить шлюз + туннель, напечатать публичный URL и ключ
#   tools/llm_service/public.sh down    # остановить всё
#   tools/llm_service/public.sh status  # показать процессы и URL
#
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="${PY:-$DIR/../../.venv/bin/python}"
RUN="/tmp/llm_public"
GATEWAY_LOG="$RUN/gateway.log"
TUNNEL_LOG="$RUN/cloudflared.log"
KEY_FILE="$RUN/gateway.key"
GW_PID="$RUN/gateway.pid"
CF_PID="$RUN/cloudflared.pid"
PORT="${GATEWAY_PORT:-8080}"
MODEL="${DEFAULT_MODEL:-gpt-oss-32k}"
mkdir -p "$RUN"

gateway_pid_alive() { [ -f "$GW_PID" ] && kill -0 "$(cat "$GW_PID")" 2>/dev/null; }
tunnel_pid_alive()  { [ -f "$CF_PID" ] && kill -0 "$(cat "$CF_PID")" 2>/dev/null; }

cmd_up() {
  # 1) шлюз на 127.0.0.1 (наружу торчит только туннель)
  if gateway_pid_alive; then
    echo "gateway уже запущен (pid $(cat "$GW_PID"))"
  else
    [ -f "$KEY_FILE" ] || openssl rand -hex 16 > "$KEY_FILE"
    GATEWAY_HOST=127.0.0.1 GATEWAY_PORT="$PORT" GATEWAY_API_KEY="$(cat "$KEY_FILE")" \
      RATE_LIMIT_PER_MIN="${RATE_LIMIT_PER_MIN:-30}" \
      MAX_CONTEXT_TOKENS="${MAX_CONTEXT_TOKENS:-8192}" \
      MAX_OUTPUT_TOKENS="${MAX_OUTPUT_TOKENS:-1024}" \
      DEFAULT_MODEL="$MODEL" \
      nohup "$PY" "$DIR/gateway.py" > "$GATEWAY_LOG" 2>&1 &
    echo $! > "$GW_PID"
  fi
  for _ in $(seq 1 40); do curl -s -o /dev/null "http://127.0.0.1:$PORT/health" && break; sleep 0.5; done
  curl -s -o /dev/null "http://127.0.0.1:$PORT/health" || { echo "шлюз не поднялся, лог: $GATEWAY_LOG"; exit 1; }
  echo "gateway: http://127.0.0.1:$PORT (pid $(cat "$GW_PID"))"

  # 2) quick-tunnel
  if tunnel_pid_alive; then
    echo "tunnel уже запущен (pid $(cat "$CF_PID"))"
  else
    nohup cloudflared tunnel --no-autoupdate --url "http://127.0.0.1:$PORT" > "$TUNNEL_LOG" 2>&1 &
    echo $! > "$CF_PID"
  fi
  URL=""
  for _ in $(seq 1 60); do
    URL="$(grep -Eo 'https://[a-z0-9-]+\.trycloudflare\.com' "$TUNNEL_LOG" | grep -v '^https://api\.' | head -1 || true)"
    [ -n "$URL" ] && break; sleep 0.5
  done
  [ -n "$URL" ] || { echo "не дождался URL туннеля, лог: $TUNNEL_LOG"; exit 1; }

  echo
  echo "=== Публичный AI-сервис поднят ==="
  echo "URL:  $URL"
  echo "KEY:  $(cat "$KEY_FILE")"
  echo "Модель: $MODEL · лимит: ${RATE_LIMIT_PER_MIN:-30}/мин"
  echo
  echo "Проверка:"
  echo "  curl $URL/health"
  echo "  curl $URL/v1/chat/completions -H 'Authorization: Bearer $(cat "$KEY_FILE")' \\"
  echo "       -H 'Content-Type: application/json' -d '{\"messages\":[{\"role\":\"user\",\"content\":\"привет\"}]}'"
}

cmd_down() {
  if tunnel_pid_alive; then kill "$(cat "$CF_PID")" 2>/dev/null || true; echo "tunnel остановлен"; fi
  if gateway_pid_alive; then kill "$(cat "$GW_PID")" 2>/dev/null || true; echo "gateway остановлен"; fi
  rm -f "$GW_PID" "$CF_PID"
}

cmd_status() {
  gateway_pid_alive && echo "gateway: работает (pid $(cat "$GW_PID"))" || echo "gateway: не работает"
  tunnel_pid_alive && echo "tunnel: работает (pid $(cat "$CF_PID"))" || echo "tunnel: не работает"
  URL="$(grep -Eo 'https://[a-z0-9-]+\.trycloudflare\.com' "$TUNNEL_LOG" 2>/dev/null | grep -v '^https://api\.' | head -1 || true)"
  [ -n "$URL" ] && echo "URL: $URL"
}

case "${1:-}" in
  up) cmd_up ;;
  down) cmd_down ;;
  status) cmd_status ;;
  *) echo "usage: $0 {up|down|status}"; exit 2 ;;
esac
