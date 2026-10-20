# Nuvio + OpenSeek (Unofficial)

> **This is not the official Nuvio app.** It is an unofficial, community build of the official
> [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV) Android TV app (GPL-3.0) with one addition:
> seek-bar **thumbnail previews from your own [OpenSeek](https://github.com/obadevyh/openseek) server**.
> Please do not ask the Nuvio team for support for this build.

Everything else is the original app: interface, addons, accounts, settings, player. If OpenSeek is off, not paired, or the server is
offline, seeking and playback behave exactly as in Nuvio (the OpenSeek code makes no request unless it is enabled **and** paired,
and every failure just means "no thumbnail").

## What was added (the whole diff)

| File | What |
| --- | --- |
| `app/src/main/java/com/nuvio/tv/openseek/OpenSeekProtocol.kt` | Pure protocol code: address/invitation parsing, OSP1 pairing derivation, request bodies, WebVTT parsing. Unit-tested against the server's shared vectors (`app/src/test/.../OpenSeekProtocolTest.kt`). |
| `app/src/main/java/com/nuvio/tv/openseek/OpenSeekRepository.kt` | Pinned-TLS HTTP client, pairing, connection status, preview session for the video that is playing, thumbnail loading/caching. |
| `app/src/main/java/com/nuvio/tv/ui/screens/settings/OpenSeekSettingsScreen.kt` | Settings > Integrations > **OpenSeek**: enable/disable, server address, pairing, connection status, forget pairing. |
| `app/src/main/java/com/nuvio/tv/ui/screens/player/OpenSeekPreview.kt` | Starts/stops the session with playback; draws the thumbnail above the seek bar. |
| `PlayerScreen.kt`, `PlayerViewModel.kt`, `SettingsScreen.kt` | A few lines each to host the overlay, expose the current stream, and list the settings section. |
| `app/build.gradle.kts`, `app/src/full/res/values/strings.xml`, `app/src/debug/res/values/strings.xml` | Separate package id `com.nuvio.tv.openseek`, label "Nuvio + OpenSeek (Unofficial)", in-app updater **off** (it would offer the official APK). |

## How it works

* **Pairing (OSP1)**: the 8-symbol code from the OpenSeek window (owner), or an `openseek://join/1?...` invitation link / typed
  invitation code (guest). PBKDF2-HMAC-SHA256 key, mutual proofs, and the server's certificate public key is **pinned** (TLS to a
  self-signed certificate; the only unpinned request is the first `POST /v1/pair/start`, which carries no secret).
* **Owner**: when a video starts, the stream URL (and only the `User-Agent`/`Referer`/`Origin` headers) goes to *your* OpenSeek server
  in the body of a pinned HTTPS request, together with the IMDb id / season / episode. The server builds thumbnails (or reuses its cache);
  the app polls the VTT and fetches tiles as you scrub. Scrub position hints are sent to the server (latest wins, at most 4 per second).
* **Guest / shared library**: only the IMDb id, season/episode, duration (and size) go to `POST /v1/lookup`; no URL. If the owner has the
  title from a different release, the thumbnails are used with the server's time mapping and are captioned "Shared thumbnails (approximate)".
  A guest the owner allowed to contribute hands over the link once, when nothing matches.
* **Display**: the thumbnail appears above the seek bar while the bar is scrubbed (key held) and for ~1.5 s after, and also on the
  quick-seek overlay when the controls are hidden. It is drawn from cached memory when possible.
* **Secrets** (access token, pin) live in the app's private storage and are never logged. Logs only contain counts and HTTP status codes.

## Build

```
# 1. backend settings (git-ignored); the same public client settings the official build uses:
#    local.properties: NUVIO_SUPABASE_URL, NUVIO_SUPABASE_ANON_KEY, SIMKL_CLIENT_ID, TV_LOGIN_WEB_BASE_URL  (see local.example.properties)
# 2. your OWN signing key (never reuse another app's key):
keytool -genkeypair -keystore my.jks -alias myalias -keyalg RSA -keysize 3072 -validity 9125
# 3. build
set NUVIO_RELEASE_STORE_FILE=my.jks & set NUVIO_RELEASE_KEY_ALIAS=myalias & set NUVIO_RELEASE_KEY_PASSWORD=... & set NUVIO_RELEASE_STORE_PASSWORD=...
gradlew :app:assembleFullRelease          # app/build/outputs/apk/full/release/app-full-arm64-v8a-release.apk
gradlew :app:testFullDebugUnitTest --tests "com.nuvio.tv.openseek.*"
```

The upstream Nuvio build needs no other changes (JDK 17, Android SDK 36, NDK 29).

## Licence and distribution

Nuvio TV is GPL-3.0 and this build is a modified version, so it is distributed under the same licence with its complete source
(this repository / patch). No official signing key, Play Store identity, branding or backend secret is used or published; the APK is
signed with a separate key and has a separate package id, so it installs next to the official app without touching its data.
Upstream copyright notices are unchanged.
