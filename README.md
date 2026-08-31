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