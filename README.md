# AI Chat

A native Android client for chatting with large language models. Bring your own API key and pick a provider — OpenRouter, OpenAI, Google, Groq, DeepSeek, Claude, Mistral, or Grok.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

## Features

- **8 providers, one interface.** Every provider is reached through an OpenAI-compatible endpoint, so switching providers never changes how you chat.
- **Bring your own key.** Keys are encrypted on-device with AES-256-GCM via Android Keystore, and validated against the provider's API before being saved.
- **Multiple saved keys.** Keep one key per provider and switch the active one without re-entering credentials.
- **Vision input.** Attach images to messages on models that support multimodal input.
- **Streaming responses.** Tokens render as they arrive, and generation can be stopped mid-flight.
- **Web search.** Optional per-request toggle for up-to-date answers.
- **Memory.** Custom instructions, long-term user memory, and AI self-managed memory.
- **Model parameters.** Temperature, top-P, max tokens, frequency penalty, and presence penalty, with reset-to-defaults.
- **Conversation history.** Multiple stored conversations, with clear and delete.
- **Themes.** AMOLED dark, light, and dynamic system theming.
- **In-app updates.** Checks a mirror for newer builds and installs them in place.

## Requirements

| | |
|---|---|
| Min SDK | 24 (Android 7.0) |
| Target SDK | 37 |
| Language | Kotlin 2.2.0 |
| UI | Jetpack Compose (Material 3) |
| Build | AGP 9.2.1, Gradle 9.4.1 |
| JDK | 17 |

## Building

```bash
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK (bumps the version automatically)
./gradlew installDebug         # build and install on a connected device
```

Release builds need signing credentials in `keystore.properties` at the project root:

```properties
RELEASE_STORE_FILE=release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

That file is gitignored. The build also falls back to Gradle properties and environment variables if the file is absent.

### Versioning

`app/version.properties` holds the current version. `assembleRelease` auto-increments `VERSION_CODE` on every run — pass `-PnoBump` to skip it:

```bash
./gradlew assembleRelease -PnoBump
```

Other useful tasks:

| Task | Purpose |
|---|---|
| `printVersion` | Print the current version name and code |
| `bumpVersion` | Increment the patch version |
| `installAndPushApk` | Build, install to device, and copy the APK to `/sdcard` |
| `UpdateMirrors` | Publish the APK and update the update mirror |
| `uploadGithubRelease` | Create a GitHub Release and upload the APK |
| `uploadRelease` | `assembleRelease` + GitHub Release |

Publishing tasks read a token from `github_token.txt` in the project root, or from the `GITHUB_TOKEN` environment variable. Both are gitignored.

## Providers

| Provider | Default model | Notes |
|---|---|---|
| OpenRouter | `openrouter/free` | Broadest catalog, includes free tiers |
| OpenAI | `gpt-5.6-sol` | GPT, o-series reasoning, and open-weight models |
| Google | `gemini-3.8-flash` | Gemini 3.x and previous generation |
| Groq | `gpt-oss-120b` | Low-latency open models |
| DeepSeek | `deepseek-v4-flash` | V4 plus V3/R1 legacy models |
| Claude | `claude-opus-4-1-20250805` | Anthropic direct, uses `x-api-key` |
| Mistral | `mistral-large-latest` | Large, Medium, Small, Codestral, Devstral |
| Grok | `grok-4.6` | xAI 4.x family |

Model IDs and default selections are defined in `app/src/main/java/com/android5/ai/Providers.kt`. Provider-specific quirks — Claude's separate auth header and version header, the different timeout profile for key validation versus chat — are handled there too.

## Project layout

```
app/src/main/java/com/android5/ai/
├── MainActivity.kt        Chat screen and Compose UI
├── ChatViewModel.kt       Chat state, streaming, model params
├── Providers.kt           Provider registry, endpoints, key validation
├── OpenRouterApi.kt       Retrofit chat API
├── SettingsManager.kt     Persisted preferences
├── SettingsActivity.kt    Settings UI
├── SetupActivity.kt       First-run key setup
├── ConversationStore.kt   Conversation persistence
├── KeyStoreHelper.kt      AES-256-GCM key encryption via Android Keystore
├── WebSearchService.kt    Web search support
├── UpdateActivity.kt      In-app update flow
├── UpdateApi.kt           Update mirror client
└── CrashActivity.kt       Crash handler
```

## License

[GPL-3.0](LICENSE). Copyright (C) 2026 LunaDevPH.