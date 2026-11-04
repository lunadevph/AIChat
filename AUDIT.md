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
