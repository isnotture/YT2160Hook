# Reverse-engineering notes for YouTube 21.43.523

Target: official package `com.google.android.youtube`, user-supplied ARM64 APKS.

Important DEX symbols found:

- `Lasbw.j : Lj$/util/Optional;`
- `Lasbw.toString()` emits `initialPlaybackVideoQualityFixedResolution=` immediately after reading field `j`.
- `Lasbw` constructor has Optional arguments in the slots corresponding to fields `i`, `j`, and `l`.
- `Lasul.D(): Ljava/lang/Integer;` reads `Lasul.c()` then `Lasbw.j`, calls `Optional.orElse(null)`, casts to `Integer`, and returns it.
- `Lasul.C(): Lbony;` is the separate preset-quality getter.
- `Lasul.c(): Lasbw;` returns the playback parameter model.
- `Lasdn.f(Lasbs, Lasbw, ...)` reads `Lasbw.j` and passes the resulting `Integer` into `Laouz.g(...)`.
- `Laouz` contains field `p : Integer` and field `q : Lbony`.
- `Laqyn.n(...)` reads `Laouz.p` and `Laouz.q` when composing `ClientAbrState`.
- `Lasul.D()` has call sites in `Lastx.bt`, `Lastx.bx`, `Lastx.by`, `Lastx.bz`, and `Lastx.as`.

Related quality-setting model:

- `Lasml(int resolution, Set availableItags, Lbbvv qualityOrdinal)` is `FixedResolution`.
- `Lasmm(Lbony)` is `PresetVideoQuality` and explicitly rejects the `ADVANCED_MENU` enum value.
- The DEX includes `QUALITY_ORDINAL_2160P` and `HD_2160` symbols.

Interpretation: the playback-start model offers a natural value to override with 2160. The exact no-2160 fallback outcome still needs on-device validation.

## Additional mappings used by the standalone quality port (21.43.523)

The following signatures were confirmed in the uploaded `base.apk` DEX files and correspond to NexAlloy's quality fingerprints:

- `Lobm.l([Laley;, int, boolean): void` — YouTube's quality-list setter. `Laley[]` contains current video formats; each `Laley` has `a: int` resolution and `b: String` display name.
- `Laske.X(Laley): void` — quality-selection receiver used when a user chooses a quality from the menu. It records manual selection for the current video; resolution `-2` is Auto.
- `Laswj.N(Lalfi;, String): Laswi;` — method containing the `Null initialPlayabilityStatus` marker. `Lalfi.M(): String` returns the current video ID.
- `Lapna.f(String): Lbony;` — per-video quality-setting getter. `Lbony.d` is the `VIDEO_QUALITY_SETTING_ADVANCED_MENU` enum constant.
- `Lapmz.<init>(Lbsbs;, int, Laktm;): void` — last-selected video quality constructor. Fields: `a` video ID, `b` resolution, `c` direction, `d` selection timestamp, `e` playback-start timestamp.
- `Lapna.n(int): Lapmz` — last-selected quality getter. `Laosh.d(boolean, Lalew;, String, Lapnh;): Laoru` is the surrounding quality-constraint calculation; its third argument is the video ID.
- `Laphk.a(Lalew;, int, int, long, String, float, Layzd;, MediaCapabilities): ClientAbrState` — method that creates the ClientAbrState sent with playback requests.
- `ClientAbrState` has `parseFrom(ByteBuffer, ExtensionRegistryLite)` and `toByteArray()` methods. The independent port adjusts the same field numbers used by Morphe's `.proto` definition: 13, 14, 16, 18, 19, 21, 26 and 30.
- `Laktm.l(long, boolean): boolean` and `Laktm.m(int, long, boolean): boolean` — boolean experiment flag accessors used by YouTube 21.42+. NexAlloy's relevant flag IDs are 45408049 (enable fixed initial resolution), 45400072 (preserve quality formats), and 45387052 (disable Shorts restart after quality change).

The independent module uses these mappings to port the behavior without packaging NexAlloy's DexKit/Morphe runtime. These are build-specific obfuscated names and must be revalidated for any other YouTube version.
- `Larfn.g(Lbqcg;): Lbbjd;` — the parent callback matching Morphe's `VideoQualityChangedFingerprint`. The standalone module scopes `Laske.X(Laley)` handling to this callback so internal quality refreshes are not treated as user selections.
