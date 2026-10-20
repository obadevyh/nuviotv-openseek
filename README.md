> **Unofficial fork.** This is **not** the official Nuvio app and is not supported by the Nuvio team.
> It is a fork of the official [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV) (Android TV) that adds optional seek-bar
> thumbnail previews from your own self-hosted [OpenSeek](https://github.com/obadevyh/openseek) server. Everything else is the original app.

## What this fork adds

* **Settings > Integrations > OpenSeek**: enable/disable, server address, pairing (code or invitation link), connection status.
* **Thumbnail previews** above the player's seek bar while scrubbing, from your OpenSeek server (owner sessions and shared-library lookups).
* If OpenSeek is off, not paired or offline, playback and seeking behave exactly as in Nuvio; nothing is sent to any server.
* A separate package id (`com.nuvio.tv.openseek`) and label "Nuvio + OpenSeek (Unofficial)", so it installs next to the official app. The in-app updater is disabled (it would offer the official APK).

Details, the list of changed files, privacy notes and build steps: [OPENSEEK.md](OPENSEEK.md).

## Download

Signed APKs are on the [Releases](../../releases) page (arm64-v8a for Nvidia Shield and most Android TVs; armeabi-v7a for older 32-bit devices).
They are signed with this fork's own key, not the official Nuvio key.

## Licence and attribution

Nuvio TV is licensed under the **GNU GPL v3.0** (see `LICENSE`); this fork is distributed under the same licence and keeps the original copyright notices, history and authorship. All credit for the app goes to the Nuvio authors and contributors; the OpenSeek additions are in a single commit on top of upstream.

---

<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <p>
    A free, open-source media app for your phone, your desktop, and the TV you already own.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/NuvioMedia/NuvioTV/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## Get Nuvio TV

- [Android TV on Google Play](https://play.google.com/store/apps/details?id=com.nuvio.app)
- [Android TV APK](https://github.com/NuvioMedia/NuvioTV/releases/latest)

## Build from source

```bash
git clone https://github.com/NuvioMedia/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullDebug
```

Nuvio TV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, a JDK, and the Android SDK.

## License

[GNU General Public License v3.0](./LICENSE)
