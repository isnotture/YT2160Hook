package io.github.yt2160hook;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Standalone extraction of the YouTube video-quality behavior used by NexAlloy/Morphe.
 * Targeted and statically checked against the user-supplied YouTube 21.43.523 APKS.
 *
 * This is an independently implemented port of the relevant behavior, not a copy of the
 * NexAlloy runtime. It keeps the Xposed side small and rewrites only quality-related ABR fields.
 */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "[YT2160Hook] ";
    private static final String TARGET_PACKAGE = "com.google.android.youtube";
    private static final String TARGET_VERSION = "21.43.523";
    private static final int DEFAULT_RESOLUTION = 2160;
    private static final int AUTO_RESOLUTION = -2;

    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean LOGGED_ABR = new AtomicBoolean(false);

    private static volatile String currentVideoId = "";
    private static volatile String manualVideoId = "";
    private static volatile int manualResolution = AUTO_RESOLUTION;
    private static volatile String manualQualityName = "";
    private static volatile Object[] availableQualities;

    private static Field qualityResolutionField;
    private static Field qualityNameField;
    private static Field lastSelectedVideoIdField;
    private static Field lastSelectedResolutionField;
    private static Field lastSelectedDirectionField;
    private static Field lastSelectedTimeField;
    private static Field lastPlaybackStartTimeField;
    private static Field advancedQualityEnumField;
    private static ThreadLocal<String> constraintVideoId = new ThreadLocal<>();
    private static ThreadLocal<Integer> manualChangeDepth = new ThreadLocal<>();

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Object value = param.args != null && param.args.length > 0 ? param.args[0] : null;
                            if (!(value instanceof Context)) return;
                            Context context = (Context) value;
                            if (!TARGET_PACKAGE.equals(context.getPackageName())) return;
                            if (INSTALLED.get() || !INSTALLING.compareAndSet(false, true)) return;
                            try {
                                if (!isExpectedVersion(context)) return;
                                installHooks(context.getClassLoader());
                            } catch (Throwable t) {
                                log("Install failed: " + t);
                            } finally {
                                INSTALLING.set(false);
                            }
                        }
                    });
        } catch (Throwable t) {
            log("Could not hook Application.attach: " + t);
        }
    }

    private static boolean isExpectedVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            if (!TARGET_VERSION.equals(info.versionName)) {
                log("Skipped unsupported version: " + info.versionName + " (expected " + TARGET_VERSION + ")");
                return false;
            }
            log("Matched official YouTube " + info.versionName);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            log("Could not verify target package version: " + e);
            return false;
        }
    }

    private static void installHooks(ClassLoader loader) throws Throwable {
        if (loader == null) throw new IllegalStateException("YouTube classloader is null");

        Class<?> qualityClass = Class.forName("Laley", false, loader);
        qualityResolutionField = qualityClass.getDeclaredField("a");
        qualityNameField = qualityClass.getDeclaredField("b");
        qualityResolutionField.setAccessible(true);
        qualityNameField.setAccessible(true);
        if (qualityResolutionField.getType() != int.class || qualityNameField.getType() != String.class) {
            throw new NoSuchFieldException("Laley quality fields did not match the APK layout");
        }

        installInitialResolutionHook(loader);
        installAvailableQualitiesHook(loader, qualityClass);
        installManualQualityHook(loader, qualityClass);
        installCurrentVideoIdHook(loader);
        installAdvancedQualitySettingHook(loader);
        installFeatureFlagHooks(loader);
        installLastSelectedQualityHooks(loader);
        installClientAbrHook(loader);

        INSTALLED.set(true);
        log("All quality hooks installed for YouTube " + TARGET_VERSION);
    }

    /** Lasul.D(): Integer is the fixed initialPlaybackVideoQualityFixedResolution getter. */
    private static void installInitialResolutionHook(ClassLoader loader) throws Throwable {
        Class<?> owner = Class.forName("Lasul", false, loader);
        Method getter = owner.getDeclaredMethod("D");
        if (getter.getReturnType() != Integer.class) {
            throw new NoSuchMethodException("Lasul.D() return type changed");
        }
        getter.setAccessible(true);
        XposedBridge.hookMethod(getter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                int selected = getInitialResolution();
                Object value = selected > 0 ? Integer.valueOf(selected) : null;
                param.setResult(value);
            }
        });
        log("Hooked Lasul.D(): initial fixed resolution");
    }

    /**
     * Lobm.l(Laley[], int, boolean) is NexAlloy's VideoQualitySetterFingerprint equivalent.
     * Its first argument is the current video's available-quality array (Auto first, then
     * descending resolutions). We retain that list to choose the best format not above 2160p.
     */
    private static void installAvailableQualitiesHook(ClassLoader loader, Class<?> qualityClass) throws Throwable {
        Class<?> owner = Class.forName("Lobm", false, loader);
        Class<?> arrayClass = Class.forName("[Laley;", false, loader);
        Method setter = owner.getDeclaredMethod("l", arrayClass, int.class, boolean.class);
        setter.setAccessible(true);
        XposedBridge.hookMethod(setter, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object arg = param.args != null && param.args.length > 0 ? param.args[0] : null;
                if (arg instanceof Object[]) {
                    availableQualities = (Object[]) arg;
                }
            }
        });
        log("Hooked Lobm.l([Laley;, int, boolean): available quality list");
    }

    /** Laske.X(Laley) is the selected-quality receiver called by YouTube's quality menu. */
    private static void installManualQualityHook(ClassLoader loader, Class<?> qualityClass) throws Throwable {
        // Restrict the quality receiver to the same parent callback NexAlloy fingerprints.
        // Otherwise the receiver could also be reached by non-user/internal quality updates.
        Class<?> changeRootClass = Class.forName("Larfn", false, loader);
        Class<?> changeRootArgument = Class.forName("Lbqcg", false, loader);
        Method changeRoot = changeRootClass.getDeclaredMethod("g", changeRootArgument);
        changeRoot.setAccessible(true);
        XposedBridge.hookMethod(changeRoot, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Integer depth = manualChangeDepth.get();
                manualChangeDepth.set(depth == null ? 1 : depth + 1);
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Integer depth = manualChangeDepth.get();
                if (depth == null || depth <= 1) manualChangeDepth.remove();
                else manualChangeDepth.set(depth - 1);
            }
        });

        Class<?> owner = Class.forName("Laske", false, loader);
        Method receiver = owner.getDeclaredMethod("X", qualityClass);
        receiver.setAccessible(true);
        XposedBridge.hookMethod(receiver, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Integer depth = manualChangeDepth.get();
                if (depth == null || depth <= 0) return;
                Object selected = param.args != null && param.args.length > 0 ? param.args[0] : null;
                if (selected == null) return;
                try {
                    int rawResolution = qualityResolutionField.getInt(selected);
                    String name = (String) qualityNameField.get(selected);
                    int normalized = normalizeResolution(name, rawResolution);
                    String videoId = currentVideoId;
                    if (videoId == null || videoId.isEmpty()) return;
                    manualVideoId = videoId;
                    manualResolution = normalized;
                    manualQualityName = name == null ? "" : name;
                    log("Manual quality selected: " + manualQualityName + " (" + normalized + "p) for video " + videoId);
                } catch (Throwable t) {
                    log("Could not read manually selected quality: " + t);
                }
            }
        });
        log("Hooked Larfn.g -> Laske.X(Laley): preserve user-selected quality only");
    }

    /**
     * Current-video ID hook corresponding to NexAlloy's VideoId fingerprint. The method's
     * first parameter is the PlayerResponse model; Lalfi.M() returns its current video ID.
     */
    private static void installCurrentVideoIdHook(ClassLoader loader) throws Throwable {
        Class<?> owner = Class.forName("Laswj", false, loader);
        Class<?> responseModel = Class.forName("Lalfi", false, loader);
        Method idGetter = responseModel.getDeclaredMethod("M");
        Method responseMethod = owner.getDeclaredMethod("N", responseModel, String.class);
        idGetter.setAccessible(true);
        responseMethod.setAccessible(true);
        XposedBridge.hookMethod(responseMethod, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object model = param.args[0];
                    String newId = (String) idGetter.invoke(model);
                    if (newId == null) newId = "";
                    String oldId = currentVideoId;
                    if (!newId.equals(oldId)) {
                        currentVideoId = newId;
                        manualVideoId = "";
                        manualResolution = AUTO_RESOLUTION;
                        manualQualityName = "";
                        availableQualities = null;
                        log("Current video changed; cleared previous manual quality state");
                    }
                } catch (Throwable t) {
                    log("Could not read current video ID: " + t);
                }
            }
        });
        log("Hooked Laswj.N(...): reset manual quality on video change");
    }

    /** Lapna.f(String) returns the current video-quality preference enum (Lbony). */
    private static void installAdvancedQualitySettingHook(ClassLoader loader) throws Throwable {
        Class<?> enumClass = Class.forName("Lbony", false, loader);
        advancedQualityEnumField = enumClass.getDeclaredField("d"); // VIDEO_QUALITY_SETTING_ADVANCED_MENU
        advancedQualityEnumField.setAccessible(true);
        if (!java.lang.reflect.Modifier.isStatic(advancedQualityEnumField.getModifiers())) {
            throw new NoSuchFieldException("Lbony.d is no longer static");
        }
        Class<?> owner = Class.forName("Lapna", false, loader);
        Method getter = owner.getDeclaredMethod("f", String.class);
        if (getter.getReturnType() != enumClass) throw new NoSuchMethodException("Lapna.f(String) return type changed");
        getter.setAccessible(true);
        XposedBridge.hookMethod(getter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    param.setResult(advancedQualityEnumField.get(null));
                } catch (Throwable t) {
                    log("Could not apply Advanced video quality setting: " + t);
                }
            }
        });
        log("Hooked Lapna.f(String): use Advanced video quality mode");
    }

    /**
     * NexAlloy's relevant YouTube boolean experiment flags:
     * 45408049 = honor the fixed initial resolution, 45400072 = preserve quality formats.
     * We deliberately leave the Shorts restart and Shorts-specific format flags unchanged:
     * this standalone port does not yet implement NexAlloy's separate Shorts menu interception.
     * In 21.42+ YouTube has both the (long, boolean) and (int, long, boolean) accessors.
     */
    private static void installFeatureFlagHooks(ClassLoader loader) throws Throwable {
        Class<?> owner = Class.forName("Laktm", false, loader);
        Method regular = owner.getDeclaredMethod("l", long.class, boolean.class);
        Method indexed = owner.getDeclaredMethod("m", int.class, long.class, boolean.class);
        regular.setAccessible(true);
        indexed.setAccessible(true);
        XC_MethodHook callback = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args == null || param.args.length < 2) return;
                Object idObject = param.args[param.args.length - 2];
                if (!(idObject instanceof Long)) return;
                long id = (Long) idObject;
                if (id == 45408049L || id == 45400072L) {
                    param.setResult(Boolean.TRUE);
                }
            }
        };
        XposedBridge.hookMethod(regular, callback);
        XposedBridge.hookMethod(indexed, callback);
        log("Hooked Laktm feature flags: fixed quality on, formats on");
    }

    /**
     * Lapmz is YouTube's LastSelectedVideoQuality model. Its fields in this build are:
     * a=video ID, b=resolution, c=direction, d=selected timestamp, e=last playback timestamp.
     */
    private static void installLastSelectedQualityHooks(ClassLoader loader) throws Throwable {
        Class<?> lastSelectedClass = Class.forName("Lapmz", false, loader);
        lastSelectedVideoIdField = lastSelectedClass.getDeclaredField("a");
        lastSelectedResolutionField = lastSelectedClass.getDeclaredField("b");
        lastSelectedDirectionField = lastSelectedClass.getDeclaredField("c");
        lastSelectedTimeField = lastSelectedClass.getDeclaredField("d");
        lastPlaybackStartTimeField = lastSelectedClass.getDeclaredField("e");
        lastSelectedVideoIdField.setAccessible(true);
        lastSelectedResolutionField.setAccessible(true);
        lastSelectedDirectionField.setAccessible(true);
        lastSelectedTimeField.setAccessible(true);
        lastPlaybackStartTimeField.setAccessible(true);

        Class<?> stateClass = Class.forName("Lbsbs", false, loader);
        Class<?> preferenceClass = Class.forName("Laktm", false, loader);
        java.lang.reflect.Constructor<?> constructor = lastSelectedClass.getDeclaredConstructor(
                stateClass, int.class, preferenceClass);
        constructor.setAccessible(true);
        XposedBridge.hookMethod(constructor, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                int resolution = getLastSelectedResolution();
                if (resolution <= 0) return; // Respect a user-selected Auto or YouTube's own state.
                try {
                    long now = System.currentTimeMillis();
                    lastSelectedResolutionField.setInt(param.thisObject, resolution);
                    lastSelectedDirectionField.setInt(param.thisObject, 0);
                    lastSelectedTimeField.setLong(param.thisObject, now);
                    lastPlaybackStartTimeField.setLong(param.thisObject, now);
                    if (lastSelectedVideoIdField.get(param.thisObject) == null) {
                        lastSelectedVideoIdField.set(param.thisObject, "");
                    }
                } catch (Throwable t) {
                    log("Could not update LastSelectedVideoQuality: " + t);
                }
            }
        });

        // During the video-quality constraint calculation, YouTube asks Lapna.n(int) for
        // the LastSelectedVideoQuality object. Mirror NexAlloy's scoped video-ID correction.
        Class<?> settingsOwner = Class.forName("Lapna", false, loader);
        Method lastSelectedGetter = settingsOwner.getDeclaredMethod("n", int.class);
        lastSelectedGetter.setAccessible(true);
        XposedBridge.hookMethod(lastSelectedGetter, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                String id = constraintVideoId.get();
                if (id == null || id.isEmpty() || getLastSelectedResolution() <= 0 || param.getResult() == null) return;
                try {
                    lastSelectedVideoIdField.set(param.getResult(), id);
                } catch (Throwable t) {
                    log("Could not bind last-selected quality to current video: " + t);
                }
            }
        });

        // Laosh.d(boolean, player, videoId, options) is the surrounding constraint builder.
        Class<?> constraintOwner = Class.forName("Laosh", false, loader);
        Class<?> playerModel = Class.forName("Lalew", false, loader);
        Class<?> options = Class.forName("Lapnh", false, loader);
        Method constraint = constraintOwner.getDeclaredMethod("d", boolean.class, playerModel, String.class, options);
        constraint.setAccessible(true);
        XposedBridge.hookMethod(constraint, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                constraintVideoId.set(param.args[2] instanceof String ? (String) param.args[2] : "");
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                constraintVideoId.remove();
            }
        });

        log("Hooked Lapmz and Lapna.n(): keep the preferred quality fixed in the player");
    }

    /** Laphk.a(...) creates ClientAbrState for SABR requests. */
    private static void installClientAbrHook(ClassLoader loader) throws Throwable {
        Class<?> owner = Class.forName("Laphk", false, loader);
        Class<?> stateClass = Class.forName(
                "com.google.android.apps.youtube.proto.ClientAbrStateOuterClass$ClientAbrState", false, loader);
        Class<?> mediaCapabilities = Class.forName(
                "com.google.android.apps.youtube.proto.streaming.MediaCapabilitiesOuterClass$MediaCapabilities", false, loader);
        Class<?>[] parameterTypes = new Class<?>[] {
                Class.forName("Lalew", false, loader), int.class, int.class, long.class,
                String.class, float.class, Class.forName("Layzd", false, loader), mediaCapabilities
        };
        Method builder = owner.getDeclaredMethod("a", parameterTypes);
        if (builder.getReturnType() != stateClass) throw new NoSuchMethodException("Laphk.a(...) return type changed");
        builder.setAccessible(true);
        XposedBridge.hookMethod(builder, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                int resolution = getAbrResolution();
                if (resolution <= 0) return; // User explicitly chose Auto.
                Object state = param.getResult();
                if (state == null) return;
                try {
                    Method serialize = state.getClass().getMethod("toByteArray");
                    byte[] original = (byte[]) serialize.invoke(state);
                    byte[] modified = ProtoWire.overrideQualityFields(original, resolution);
                    if (modified == null) return;

                    Class<?> registryClass = Class.forName("com.google.protobuf.ExtensionRegistryLite", false, loader);
                    Method emptyRegistry = registryClass.getDeclaredMethod("getEmptyRegistry");
                    emptyRegistry.setAccessible(true);
                    Object registry = emptyRegistry.invoke(null);
                    Method parseFrom = stateClass.getDeclaredMethod("parseFrom", ByteBuffer.class, registryClass);
                    parseFrom.setAccessible(true);
                    Object rebuilt = parseFrom.invoke(null, ByteBuffer.wrap(modified), registry);
                    param.setResult(rebuilt);
                    if (LOGGED_ABR.compareAndSet(false, true)) {
                        log("Patched ClientAbrState: sticky/manual resolution=" + resolution + "p");
                    }
                } catch (Throwable t) {
                    log("Could not rewrite ClientAbrState; original request state retained: " + t);
                }
            }
        });
        log("Hooked Laphk.a(...): maintain target resolution in SABR requests");
    }

    private static int getInitialResolution() {
        if (isManualChoiceForCurrentVideo()) {
            return manualResolution > 0 ? manualResolution : 0;
        }
        return getPreferredAvailableResolution();
    }

    /** The value used for the player-side LastSelectedVideoQuality object. */
    private static int getLastSelectedResolution() {
        if (isManualChoiceForCurrentVideo()) return 0;
        return getPreferredAvailableResolution();
    }

    /** The value used in every SABR ABR state, including manual fixed choices. */
    private static int getAbrResolution() {
        if (isManualChoiceForCurrentVideo()) return manualResolution > 0 ? manualResolution : 0;
        return getPreferredAvailableResolution();
    }

    private static boolean isManualChoiceForCurrentVideo() {
        String id = currentVideoId;
        return !id.isEmpty() && id.equals(manualVideoId);
    }

    /**
     * Matches NexAlloy's fallback: choose the highest available non-Auto resolution not above
     * the configured maximum. If the list is not populated yet, keep 2160 as the requested cap.
     */
    private static int getPreferredAvailableResolution() {
        Object[] qualities = availableQualities;
        if (qualities == null || qualities.length == 0) return DEFAULT_RESOLUTION;

        int bestAtOrBelowCap = -1;
        for (Object quality : qualities) {
            if (quality == null) continue;
            try {
                int raw = qualityResolutionField.getInt(quality);
                String name = (String) qualityNameField.get(quality);
                int resolution = normalizeResolution(name, raw);
                if (resolution <= 0 || resolution == AUTO_RESOLUTION) continue;
                if (resolution <= DEFAULT_RESOLUTION && resolution > bestAtOrBelowCap) {
                    bestAtOrBelowCap = resolution;
                }
            } catch (Throwable ignored) {
                // Ignore malformed quality entries; other entries remain usable.
            }
        }
        // Match Morphe's ceiling behavior: if no listed format is at or below the cap,
        // keep requesting the cap instead of selecting a higher-than-requested format.
        return bestAtOrBelowCap > 0 ? bestAtOrBelowCap : DEFAULT_RESOLUTION;
    }

    private static int normalizeResolution(String name, int rawResolution) {
        if (name != null && rawResolution != AUTO_RESOLUTION
                && !name.startsWith(Integer.toString(rawResolution))) {
            int p = name.indexOf('p');
            if (p > 0) {
                try { return Integer.parseInt(name.substring(0, p)); }
                catch (NumberFormatException ignored) { }
            }
        }
        return rawResolution;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + message);
    }
}
