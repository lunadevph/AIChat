# AI Chat

A native Android client for chatting with large language models. Bring your own API key and pick a provider — OpenRouter, OpenAI, Google, Groq, DeepSeek, Claude, Mistral, Grok, or NVIDIA NIM.

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)

## Features

- **9 providers, one interface.** Every provider is reached through an OpenAI-compatible endpoint, so switching providers never changes how you chat. Add your own: any OpenAI-compatible base URL with an optional hand-listed model catalog, from Settings → AI & Provider → API Keys.
- **Bring your own key.** Keys are encrypted on-device with AES-256-GCM via Android Keystore, and validated against the provider's API before being saved.
- **Multiple saved keys.** Keep one key per provider and switch the active one without re-entering credentials.
- **Vision input.** Attach images to messages on models that support multimodal input.
- **Streaming responses.** Tokens render as they arrive, and generation can be stopped mid-flight, including while a retry is counting down.
- **Retries instead of errors.** A rate-limited request (HTTP 429) is held and retried with a doubling backoff, honouring the provider's Retry-After, with the countdown shown in the transcript.
- **Web search.** Optional per-request toggle for up-to-date answers.
- **Tool calls.** Native structured function calls support scoped file operations, downloads, web search, memory and time. Streaming calls are assembled before execution, and each result is returned with its matching call ID. Claude uses native `tool_use` and `tool_result` blocks. Tool calls can be switched off in settings. Shell and exec commands run with app permissions and can access private app data; the working directory is not a filesystem sandbox. Commands have bounded output, a timeout of up to 60 seconds, and cancellation when generation stops.
- **Password-protected backups.** Export conversations, attached images, settings and API keys to an AES-GCM encrypted `.ai` file. A password-derived key with a random salt protects new backups; keep the password to restore on another device. Older certificate-derived backups remain importable but their encryption does not provide confidentiality.
- **Device storage.** Once you grant "All files access" in Settings → AI settings → Device storage, the assistant can read, search, write, move, copy and delete anywhere under `/storage/emulated/0/…` with absolute paths — the entire shared storage, not just Downloads, Documents and pictures. The app works fully without it; the tools stay in their private workspace.
- **Tool file history.** Everything the assistant writes in its workspace is listed in its own screen — size, age, a preview, per-file delete and clear-all — and each reply links to the files it touched.
- **Memory.** Custom instructions, long-term user memory, and AI self-managed memory.
- **Model parameters.** Temperature, top-P, max tokens, frequency penalty, and presence penalty, with reset-to-defaults.
- **Conversation history.** Multiple stored conversations, with clear and delete.
- **Material 3 Expressive.** Dynamic color, a centred top bar, split button, toggle button, morphing loading and wavy progress indicators, the Material shape library and expressive type styles.
- **Appearance.** Dynamic, light, AMOLED, midnight, ocean, forest and sunset themes, with outlined, filled, tonal and classic chat bubbles. Legacy appearances migrate automatically.
- **Self-updating.** Checks a mirror for newer builds, verifies the downloaded APK against the mirror's published SHA-256, installs in-app with live progress and hands it to the installer — no browser hop. Falls back to the release page if the install is blocked. The mirror format is versioned and additive, so older builds keep reading newer mirrors.
- **Optional device storage.** Off by default; when granted, the tools can also read and save files in the shared folders (Downloads, Documents, pictures) instead of only their own workspace.

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

`app/version.properties` holds a semantic display version (`1.0.0`) and an independent,
always-increasing Android `VERSION_CODE`. Release builds increment the patch version
and version code once. Use `-PversionBump=minor` or `-PversionBump=major` for a larger
release, or `-PnoBump` to rebuild the current version. Calendar versions are retired;
the internal version code stays above older releases so installed apps can update:

```bash
./gradlew assembleRelease -PnoBump
```

Other useful tasks:

| Task | Purpose |
|---|---|
| `printVersion` | Print the current version name and code |
| `bumpVersion` | Increment patch and version code (or use `-PversionBump=minor` / `major`) |
| `verifyVersioning` | Check version increments without changing files |
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
| NVIDIA | `nvidia/llama-3.3-nemotron-super-49b-v1.5` | NIM-hosted Nemotron and open models |

Model IDs and default selections are defined in `app/src/main/java/com/android5/ai/Providers.kt`. Provider-specific quirks — Claude's separate auth header and version header, the different timeout profile for key validation versus chat — are handled there too.

## Project layout

```
app/src/main/java/com/android5/ai/
├── MainActivity.kt        Chat screen and Compose UI
├── ModelPickerSheet.kt    Model picker
├── ChatViewModel.kt       Chat state, streaming, model params
├── AiTools.kt             In-band tool calls (<read/>, <write/>, <download/>, …)
├── ToolFiles.kt           Workspace view used by the tool file history
├── ToolFilesActivity.kt   Tool file history screen
├── AppLogo.kt             The app's mark, one composable for every screen
├── SelectionIndicator.kt  Fixed-width check slot for selectable rows
├── DeviceStorage.kt       Optional access to the user's shared folders
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
├── UpdateInstaller.kt     APK download and installer hand-off
└── CrashActivity.kt       Crash handler
```

## License

[GPL-3.0](LICENSE). Copyright (C) 2026 LunaDevPH.
Settings includes encrypted backup and restore with options for conversations, images and API keys. API keys are excluded by default.

The Updates screen offers Release and Debug channels with separate feeds and download caches. Publish a Debug-channel build with `-PupdateChannel=debug` on the release build and mirror tasks; it uses release signing for installation compatibility. The displayed six-digit `BUILD_NUMBER` is independent of the increasing Android `VERSION_CODE`.


### ChatGPT connections

Accounts & API keys offers **Continue with ChatGPT** separately from API-key providers. It uses OpenAI public-client registration, PKCE and a loopback browser callback. The issued client ID, validated identity, grant and refresh credentials stay together per account registration. Credentials are Keystore-encrypted in Android no-backup storage; portable backups do not include them.

ChatGPT-plan requests use the public Responses endpoint with streaming and storage disabled, account-specific model discovery, namespaced local tools, and preserved response continuation items. They do not use ChatGPT private backend endpoints or silently fall back to API-key billing. Access depends on the account, workspace, granted permissions and current OpenAI eligibility/limits.

Reference: [registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations).

The user verified ChatGPT browser sign-in on November 19, 2026. Credential persistence across restarts and live inference still need separate verification. Automated checks cover OAuth callback binding, PKCE, ID-token signatures/claims, catalog selection, Responses request construction, and saved tool-item replay.


### Debug AppLogger

Debug builds, including signed Debug-channel builds, automatically capture all available Logcat levels for the app process into `files/app_logs/` in the app's private directory. Ordinary Release-channel builds do not start AppLogger. Files rotate at 1 MiB with up to eight retained files, and fatal exceptions are also written directly before crash handling. Common credential headers, token fields and OAuth callback parameters are redacted before writing; review diagnostic exports before sharing them.

Open **Diagnostics → AppLogger** to inspect the directory/status, export a ZIP or clear captured logs. Collection continues after clearing. Log files are excluded from Android cloud backup and device transfer. Android restricts logs from other processes; this is app-process capture, not unrestricted device logging. Device capture and document-provider export still require a connected-device check.


ChatGPT connection errors now distinguish DNS failures. After a DNS error, **Secure DNS fallback** can be enabled in the ChatGPT connection card; retry sign-in afterward. It uses Cloudflare HTTPS DNS only after system DNS fails, and only for `auth.openai.com` and `api.openai.com`. OpenAI HTTPS hostname/certificate checks remain enabled. This does not change Android or the browser's DNS settings.

The loopback callback page attempts to reopen the initiating Setup or Settings screen through a package-targeted Android intent. If a browser blocks automatic navigation, tap **Return to AI Chat**. The app accepts only a short-lived, single-use foreground ticket created for the active flow; OAuth codes/tokens remain on the state-checked loopback exchange.
