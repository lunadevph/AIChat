# AGENTS.md

Guidance for AI coding agents (and humans) working in this repository.

## Always update the release notes

**Every release must ship with updated release notes.** `app/changelog.txt` is the
single source of truth for them:

- `uploadGithubRelease` uses its contents as the GitHub Release body.
- `UpdateMirrors` embeds it into `mirror.json` (`changelog` field) for the in-app updater.

So, before running any release/publish task (`bumpVersion`, `assembleRelease`,
`uploadRelease`, `uploadGithubRelease`, `UpdateMirrors`):

1. Update `app/changelog.txt` to describe the changes in this release.
2. Write it as one bullet per user-visible change, each line starting with `- `.
   Keep it plain language and end the file with a single trailing newline.
3. Do not release with an empty, stale, or copied-over changelog. If the release
   contains no user-visible changes, say so explicitly in a bullet.
4. Commit the changelog together with the version bump.

Keep it user-facing: describe what changed for the person using the app, not the
internal implementation. Do not add a version header inside the file — the version
comes from `app/version.properties`.

## Project

Native Android client for chatting with LLM providers over OpenAI-compatible APIs.
Kotlin + Jetpack Compose (Material 3). See `README.md` for features and layout.

- Min SDK 24, target/compile SDK 37, AGP 9.2.1, Gradle 9.4.1, JDK 17+ (CI uses 21).
- All providers are reached through an OpenAI-compatible endpoint. Endpoints, model
  IDs, defaults, and key validation live in `Providers.kt`.
- Claude (`Anthropic`) is the exception: it uses `x-api-key` + `anthropic-version`
  instead of `Authorization`, and its model catalog shares the chat base URL.

## Build and verify

```bash
./gradlew assembleDebug          # debug APK — use this to verify changes compile
./gradlew assembleRelease        # release APK (auto-bumps the version)
./gradlew printVersion           # current version name/code
```

- There is no unit/instrumentation test suite; validate changes by compiling
  (`assembleDebug`) and, for UI work, reasoning through the Compose state.
- Network calls to provider APIs should be exercised deliberately — the live APIs
  reject requests differently per provider (invalid keys, rate limits and
  account-level restrictions each surface as a different error).

## Versioning

- `app/version.properties` is auto-generated calendar versioning (`YY.MM.DD.build`,
  e.g. `261017.001`); `VERSION_CODE = YYMMDD * 1000 + build`.
- Release builds (`assembleRelease`, `uploadRelease`, …) bump the version once. AGP
  resolves `versionName`/`versionCode` at **configuration** time, so the bumped
  values are computed there and stamped into the APK, while `BumpAppVersions`
  persists them to `version.properties` — the APK and the file always agree.
- Pass `-PnoBump` to build without bumping (e.g. to rebuild an existing release).
- To bump by hand: `./gradlew bumpVersion`, then build with `-PnoBump`.
- Never publish a release whose APK version does not match `version.properties`;
  that mismatch is exactly the bug the configuration-time resolve prevents.

## Release / publishing

```bash
./gradlew uploadRelease          # assembleRelease + create a GitHub Release
./gradlew uploadGithubRelease    # create a GitHub Release and upload the existing APK
./gradlew UpdateMirrors          # publish APK + update the update mirror
```

- Release builds need signing credentials in `keystore.properties` (gitignored) or
  the `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` / `RELEASE_KEY_ALIAS` /
  `RELEASE_KEY_PASSWORD` Gradle properties / environment variables.
- Publishing reads a token from `github_token.txt` (gitignored) or `GITHUB_TOKEN`.
- `uploadGithubRelease` tags the release as `v$VERSION_NAME` at the branch HEAD at
  creation time. Push commits **before** releasing so the tag matches the built code.

## Conventions

- Keep changes consistent with the surrounding code (Compose idioms, existing naming
  and file organization).
- User-facing strings belong in `app/src/main/res/values/strings.xml`; do not leave
  hard-coded English in Compose code.
- Do not commit secrets or signing material (`keystore.properties`,
  `local.properties`, `github_token.txt`, `*.jks` are gitignored).
- Prefer small, focused commits with a clear summary of what changed and why.
