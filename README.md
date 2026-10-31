# YT2160Hook — standalone YouTube quality preference for LSPosed

**Target:** the official `com.google.android.youtube` APK, version **21.43.523**.  The hook was mapped against the user-provided ARM64 APKS split set. It is a Java-only Xposed module and does not require a native ARM library.

## What it does

- Requests **2160p** as the default fixed resolution.
- When YouTube exposes its available-quality list, selects the highest available resolution not exceeding 2160p. If the list is not available yet, or every listed quality is above the cap, it keeps requesting 2160p and lets YouTube negotiate a compatible stream.
- Keeps the preferred resolution in the player-side last-selected-quality state and in the `ClientAbrState` fields used by SABR requests, so automatic ABR does not immediately replace the target with a lower quality.
- Respects a quality selected manually in the **regular video** quality menu, including **Auto**, for the current video. That manual choice is cleared when the current video changes.
- Uses YouTube's Advanced quality mode internally while the module is active, without replacing the quality menu.
- Enables the fixed-resolution and quality-format flags used by this YouTube build. It leaves Shorts-specific restart behavior untouched.

The algorithm follows the relevant behavior of NexAlloy/Morphe's `RememberVideoQuality` implementation, but the module does not bundle Morphe, DexKit, or the NexAlloy patch runtime. The hooks are version-specific reflection hooks against the supplied 21.43.523 APK.

## Compatibility guard

The module refuses to hook a YouTube version other than exactly `21.43.523`. It also checks the expected obfuscated method signatures and field types before installing each hook. A future YouTube update will therefore require a fresh APK analysis and a code update rather than blindly applying these names.

## Build

Requirements: Android SDK (platform 35), JDK 17+, and Gradle 8.9+.

```bash
gradle :app:assembleDebug
```

The debug APK is generated at:

`app/build/outputs/apk/debug/app-debug.apk`

The Xposed API is compile-only (`de.robv.android.xposed:api:82`) and is not packaged in the module.

A GitHub Actions workflow is included to build the debug APK and upload it as an Actions artifact.

## Install and test

1. Install the module APK.
2. In LSPosed, enable **YouTube 2160P Hook** and select the official YouTube app as its scope.
3. Force-stop YouTube and launch it again.
4. Check the LSPosed log for `[YT2160Hook] Matched official YouTube 21.43.523` and `All quality hooks installed`.
5. Test at least: (a) a video with 2160p, (b) a video whose maximum is 1440p or 1080p, (c) manually selecting a lower resolution, and (d) manually selecting Auto.

To disable it, turn off the module or remove YouTube from its LSPosed scope, then force-stop YouTube.

## Known limitations / verification status

- This source has been statically mapped against the supplied APK. It has **not yet been built with an Android SDK or tested on a live YouTube installation** in this environment.
- Playback behavior can depend on YouTube's account, player layout, codec, network and server response. Videos that do not expose a standard quality list and live streams need separate runtime checks.
- Shorts use a separate quality-menu path in NexAlloy. This first standalone port does not yet include that separate Shorts click/format interception, so manual Shorts quality changes are not guaranteed to be preserved. The module has been kept from changing Shorts-specific restart flags; disable the module when manually managing Shorts quality until that path is implemented and tested.
- The hook intentionally only targets the exact app version above. Do not enable it on ReVanced, YouTube Music, or other YouTube versions.

## Credits and license

This is an independently written extraction/port of the relevant quality-selection behavior from [NexAlloy](https://github.com/NexAlloy/NexAlloy) and [Morphe patches](https://github.com/NexAlloy/morphe-patches). The relevant upstream projects are licensed under **GNU GPL v3**; the algorithmic behavior and protobuf field mapping are attributed in `NOTICE`. This derivative project is also distributed under GPL-3.0. See `LICENSE`.
