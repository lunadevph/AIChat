# App audit — 2026-11-04

Reviewed chat requests, streaming and tool replay; chat persistence and legacy migration; backup import/export; API-key storage; update channels, downloads and installation; workspace file operations; Compose settings and provider artwork; and manifest/file-sharing configuration.

## Fixes

| Area | Finding and change |
| --- | --- |
| Attachments | Bounds-only bitmap decoding intentionally returns no bitmap. Removed the null-result check that rejected every image before the real decode. |
| Tool history | A single chat bubble can contain sequential tool rounds. Save each round separately and reconstruct assistant/tool messages in execution order, retaining provider signatures and the final answer. App error text is excluded from replay. Older saved records remain readable. |
| Conversation storage | Validate identifiers at all store entry points, reject the reserved index database name, and use insertion order to break equal message timestamps. |
| Database upgrades | Add optional message columns missing from older databases rather than treating a failed query as an empty chat. |
| Save reliability | Commit message changes before updating the conversation index. Each message replacement remains transactional. |
| Legacy import | Preserve malformed source data and resume partially completed imports without dropping the remaining chats or replacing already imported chats. |
| Backup validation | Reject reserved conversation IDs and duplicate conversation/message IDs before writing imported records. |
| Workspace | Bound preview reads instead of loading whole files; cap listing traversal; avoid directory-link recursion; delete directory entries without recursively following symbolic links; report failed clears. |
| API keys | Serialize creation of the Keystore master key to avoid concurrent first-use initialization races. |
| UI resources | Removed the redundant partial English resource file; use configuration-aware Compose resources for callback messages. |
| Platform compatibility | Keep API 26 navigation-bar attributes in version-qualified themes; check notification permission before posting updates. |
| Artwork | Convert SVG arc commands in seven more provider icons to Android-compatible cubic curves. |

## Verification

- The final source compiles and all 59 unit tests pass, including the symbolic-link deletion test.
- Provider vector paths parse after normalization; whitespace checks pass.
- App-source lint analysis completed. Its first report contained 389 definite errors (368 redundant English-resource translation errors and 21 configuration-resource errors). Those were corrected; a subsequent source analysis reduced this to five definite findings. The five remaining Setup resource/indentation findings were corrected afterward and compiled/tested, but were not rescanned.
- Full `lintDebug` and report generation remain incomplete: required Android test-library artifacts are unavailable in the local cache, and the online attempt did not resolve them. No clean full-lint result is claimed. Existing deprecation and other advisory warnings remain.
- No release/version bump or publication was performed.

## Limits

This is a source and automated-check audit, not a guarantee that every runtime defect has been found. No connected-device walkthrough or paid/live provider requests were performed. Image picking, old-database migration, system backup/restore, unknown-source installation and visual layout still need device verification. Changes are local until explicitly published.


# UI and request-size follow-up — 2026-11-05

- Network context bounds tool output and removes only complete older turns when needed. Latest user input, current tool-call arguments and provider signatures remain intact. Saved messages and files are unchanged.
- HTTP 413 receives at most two progressively smaller request retries. Final continuation uses completed tool-result summaries with further actions paused. Writes are not rerun. Unrecoverable oversized requests receive a specific message explaining that completed operations are retained.
- Shared shape tokens, morphing primary buttons, section headers and surface colors carry across Settings, Setup, appearance/provider/model selectors, backups, workspace and Updates. Setup/Settings/workspace content has readable width limits on large displays. Controls use minimum heights where fixed heights could clip larger text.
- Chat action icons inherit the actual surface content color for the chosen bubble style.
- Workspace listing and mutations run on IO with loading, retry and failure feedback. Busy backup sheets cannot be dismissed by dragging/back during an operation. Update icons distinguish checking and unpublished feeds.
- Validation: debug build succeeds; all 68 unit tests pass, including signed-call preservation, result JSON/status preservation, bounded retries and cancellation. No Android device is connected; visual/device checks and live provider 413 reproduction remain outstanding. This is source/state review, not a pixel-verified UI sign-off. Changes are not published.


# ChatGPT sign-in and connection settings — 2026-11-08

- Added the documented public-client ChatGPT sign-in flow. Browser-only authentication, fresh state/nonce/PKCE, exact loopback callback binding, signed identity validation, account/client binding, encrypted no-backup credentials, serialized refresh rotation and best-effort remote revocation are implemented. Identity sign-in alone does not enable plan usage.
- Account-specific models and Responses-only streaming respect the public ChatGPT-plan contract. Tool namespaces and continuation items are preserved. Account changes/sign-out stop active ChatGPT calls. No OAuth credential is exposed as an API key, backed up, logged, or sent to a custom provider.
- Replaced the API-key accordion with a searchable connection list, saved-key filtering and a focused editor. Testing/activation/removal are explicit. Validation failures preserve the previous key. Removal requires confirmation. Custom-provider management remains available. API-key 403s are treated as access failures rather than proof that a key is invalid.
- Fixed selection of unavailable default models after catalog loading; use an actual returned model instead.
- Verification is local build/unit coverage, not a claim of successful live sign-in. A connected Android device and consenting eligible account are required for end-to-end OAuth, platform storage/revocation and billing-route validation. No publication was performed.

Final local verification for the 2026-11-08 follow-up: debug APK builds, all 79 tests pass, and whitespace checks pass. Public OIDC discovery confirms the configured issuer, JWKS URL and RS256 signing support. No connected Android device or consenting live account was available for end-to-end OAuth/inference validation.


# Debug AppLogger — 2026-11-08

- Debug builds capture available app-process Logcat buffers/levels into private `files/app_logs/`, with a PID-filter fallback for older Logcat versions. Ordinary Release builds skip collector startup and diagnostics UI. No broad log-reading permission is added.
- Rotating files retain at most eight 1 MiB files; each record is UTF-8 bounded and common credentials are redacted before disk writes. Fatal exceptions receive a direct synchronous record. Cloud backup/device transfer exclude these logs.
- Diagnostics offers status/path/size, ZIP export and confirmed clearing. Local tests cover redaction, append/rotation/clear, Unicode bounds and PID filtering. Actual device Logcat capture and document-provider export remain unverified without a connected device.

AppLogger verification: debug APK compiles and all 83 unit tests pass with zero failures; whitespace checks pass. No connected-device capture/export test has been performed.
