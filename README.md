# AI Advent Challenge

Minimal Android app built with **Jetpack Compose + Kotlin** that sends a prompt to an LLM through an **OpenAI-compatible API** and shows the response on screen.

## Features

- Sends `POST {baseUrl}/v1/chat/completions` with a user prompt
- Shows the assistant's reply in the UI and logs it to Logcat (tag `LLM`)
- Provider, endpoint and model are configurable — works with OpenAI, DeepSeek, OpenRouter, Ollama, etc.
- The API key is **not baked into the APK**: you enter it in the app once, and it is stored encrypted in the Android Keystore

## Tech stack

- Kotlin 2.2, AGP 8.9, Gradle 8.14 (wrapper)
- Jetpack Compose, Material 3, Navigation Compose, ViewModel
- Networking via `HttpURLConnection` + built-in `org.json` (no extra dependencies)
- `minSdk 30`, `compileSdk/targetSdk 35`

## Getting started

### Prerequisites

- JDK 17
- Android SDK (platform 35)

### Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio, sync, and press Run.

### Configuration

Create/update `local.properties` (this file is gitignored):

```
LLM_BASE_URL=https://api.openai.com   # used to build the endpoint when LLM_ENDPOINT is empty
LLM_ENDPOINT=                          # optional: full chat completions URL (takes precedence)
LLM_MODEL=gpt-4o-mini                  # model name
```

The endpoint used is `LLM_ENDPOINT`, or `LLM_BASE_URL + "/v1/chat/completions"` when it is empty.

### Usage

1. Launch the app.
2. On the first screen paste your API key and press **Save** (it is encrypted and stored in the Android Keystore).
3. Type a prompt and press **Send** — the answer appears below.

## MCP (Model Context Protocol)

The app includes a minimal MCP client (Streamable HTTP, built on `HttpURLConnection` + `org.json`, no extra dependencies). It connects to an MCP server and lists its tools.

- Open the **MCP** screen from the home screen.
- By default it points to a public demo server: `https://mcp-http-demo.arcade.dev/mcp` (tool `lorem`).
- Presets **«Публичный»** and **«Локальный»** fill the endpoint and connect in one tap.
- The endpoint is saved; `MCP_ENDPOINT` in `local.properties` sets the default.

### Running the local MCP server (optional)

A small demo server lives in `tools/mcp_server.py` (Python + the official MCP SDK).

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install "mcp[cli]"
python tools/mcp_server.py
```

It starts on `http://127.0.0.1:8000/mcp` with demo tools (`add`, `multiply`, `current_time_utc`,
and `lorem`, which generates Lorem Ipsum through a public lorem API — same as the public demo server),
plus a scheduler/background-tasks demo (`schedule_reminder`, `check_due_reminders`, `list_reminders`,
`start_metrics_collector`, `stop_metrics_collector`, `get_metrics_summary`) backed by SQLite
(`tools/mcp_scheduler.db`) — a background thread keeps ticking for as long as the server process is
alive, independent of whether an Android client is connected.
It also ships a composition/pipeline demo (Day 19): `search` (local demo knowledge base + Wikipedia),
`summarize` (extractive summary + stats), `save_to_file` (writes to `tools/pipeline_outputs/`) and
`run_pipeline`, an orchestrator that chains `search → summarize → save_to_file` server-side and returns
a trace of each step's in/out so data flow between tools is verifiable. The model can either call
`run_pipeline` in one shot or build the chain itself via function calling.
From the Android emulator use `http://10.0.2.2:8000/mcp` (the app's **«Локальный»** preset).

### Multi-server orchestration (Day 20)

A second local server `tools/mcp_server2.py` (notes: `note_save`, `note_list`, `note_find`,
`note_delete`, SQLite `tools/mcp_notes.db`) runs on `http://127.0.0.1:8001/mcp`. Start both servers
and the agent connects to **all** endpoints from the settings field «MCP endpoints (через запятую)»
(defaults: `MCP_ENDPOINT` + `MCP_ENDPOINT2` from `local.properties`), aggregates their tools into one
function-calling list and routes each `tools/call` to the right server by tool name. Logs show the
routing: `mcp: servers=[...]` and `mcp: [<url>] call name(...)`. A long cross-server flow:
`search` + `summarize` (server 1) → `note_save` → `note_find` (server 2).

> **HTTP 421 «Invalid host header»?** The MCP SDK (2.x) validates the `Host` header
> (DNS-rebinding protection). `tools/mcp_server.py` already listens on `0.0.0.0` and allows
> `10.0.2.2` in `TransportSecuritySettings.allowed_hosts` — if you still get 421, make sure
> no old server instance is holding port 8000: `lsof -ti tcp:8000 | xargs kill -9`.

## Provider examples

| Provider | LLM_BASE_URL | LLM_ENDPOINT | LLM_MODEL |
|----------|--------------|--------------|-----------|
| OpenAI | `https://api.openai.com` | *(empty)* | `gpt-4o-mini` |
| DeepSeek (direct) | `https://api.deepseek.com` | *(empty)* | `deepseek-chat` |
| DeepSeek via OpenRouter | `https://openrouter.ai/api` | `https://openrouter.ai/api/v1/chat/completions` | `deepseek/deepseek-chat` |

> **DeepSeek note:** the official API rejects requests from certain countries/regions
> (`403 unsupported_country_region_territory`). If your region is blocked, use a VPN
> or route through a provider like OpenRouter (see above).

## Security

- The API key never leaves the device in plaintext and is never committed: it is entered at
  runtime, encrypted with a key held in the Android Keystore, and stored in private prefs.
- `local.properties` is in `.gitignore`, so secrets stay local.
- Cleartext HTTP is only allowed for `localhost` / `10.0.2.2` (emulator host) — everything else
  must be HTTPS.