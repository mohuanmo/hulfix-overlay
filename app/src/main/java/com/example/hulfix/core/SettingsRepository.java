package com.example.hulfix.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.List;

import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;

/**
 * Reads module settings from inside the hooked process (SystemUI).
 *
 * <p>Read path: {@link XSharedPreferences} (the standard Xposed mechanism) with a
 * file-mtime poller as a self-healing fallback, plus an explicit broadcast from the app
 * process for instant hot-reload whenever the user changes a setting.</p>
 */
public final class SettingsRepository {

    public static final String PKG = "com.example.hulfix";
    public static final String PREF_NAME = "hulfix_settings";
    public static final String ACTION_SETTINGS_CHANGED =
            "com.example.hulfix.action.SETTINGS_CHANGED";
    /**
     * App 广播携带的全量设置 JSON（{@link Settings#toJson()}）。
     *
     * <p><b>主通道</b>：SystemUI（system_app 域）在不少 ROM 上被 SELinux 挡住，读不到
     * App 私有目录的 prefs 文件——XSharedPreferences 与 mtime 轮询都不可靠。因此以本
     * extra 为第一判据，收到即解析应用；文件通道降级为广播丢失时的兜底。</p>
     */
    public static final String EXTRA_SETTINGS_JSON = "settings_json";

    private static final String PREFS_PATH =
            "/data/data/" + PKG + "/shared_prefs/" + PREF_NAME + ".xml";
    private static final long MTIME_POLL_INTERVAL_MS = 250;

    /** App 每次写设置都会把它 +1：用于可靠地发现"设置变了"（比 mtime 可靠）。 */
    public static final String KEY_REVISION = "hulfix_revision";

    private static volatile SettingsRepository sInstance;

    /** 设置重新加载后的回调（用于"切样式立即生效"的即时重绘）。 */
    public interface Listener {
        void onSettingsReloaded(Settings settings);
    }

    private final java.util.List<Listener> mListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * 主线程 Handler：惰性创建。模块类在 zygote fork 阶段（主 Looper 就绪前）就会
     * 实例化，构造期内 {@code new Handler(Looper.getMainLooper())} 会 NPE。
     */
    private final MainHandler mHandler = new MainHandler();
    private XSharedPreferences mPrefs;
    private Settings mSettings;
    private long mLastFileMtime = -1;
    private long mLastRevision = -1;
    private long mLastMtimeCheck = 0;
    private boolean mStarted = false;

    private SettingsRepository() {
        mSettings = new Settings();
        mPrefs = new XSharedPreferences(PKG, PREF_NAME);
        readAll();
    }

    public static SettingsRepository instance() {
        if (sInstance == null) {
            synchronized (SettingsRepository.class) {
                if (sInstance == null) {
                    sInstance = new SettingsRepository();
                }
            }
        }
        return sInstance;
    }

    public void addListener(Listener l) {
        if (l != null && !mListeners.contains(l)) mListeners.add(l);
    }

    public void removeListener(Listener l) {
        if (l != null) mListeners.remove(l);
    }

    /** Registers the settings-changed receiver. Safe to call from any thread. */
    public void start(Context context) {
        if (mStarted) return;
        mStarted = true;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    // 主通道：广播自带全量 JSON，不依赖任何文件读取
                    String json = intent != null
                            ? intent.getStringExtra(EXTRA_SETTINGS_JSON) : null;
                    Settings fresh = Settings.fromJson(json);
                    if (fresh != null) {
                        XposedBridge.log("HULFix: settings broadcast received ("
                                + (json == null ? 0 : json.length()) + " chars)");
                        mHandler.post(() -> applySettings(fresh, "broadcast-json"));
                        return;
                    }
                    // 兜底：老版本 App 或不带 JSON 的广播 → 走文件通道
                    XposedBridge.log("HULFix: settings broadcast without valid json, "
                            + "falling back to file (len="
                            + (json == null ? -1 : json.length()) + ")");
                    reload();
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_SETTINGS_CHANGED);
            // 自定义广播、跨进程（来自本模块 App 进程）：必须 EXPORTED，否则
            // Android 14（SystemUI target 34）既会因缺 flag 抛 SecurityException，
            // 收不到跨进程广播
            PlatformCompat.registerReceiver(context, receiver, filter,
                    mHandler.get(), true);
            XposedBridge.log("HULFix: settings broadcast receiver registered");
        } catch (Throwable t) {
            mStarted = false;
            XposedBridge.log("HULFix: FAILED to register settings receiver: " + t);
        }
    }

    /**
     * Force a fresh read from the backing store. */
    public void reload() {
        mHandler.post(this::readAll);
    }

    /**
     * 同步重读设置并返回最新快照（每条通知进入时调用，150ms 节流）。
     *
     * <p><b>为什么存在</b>：设置到 hook 进程的通道有两条（App 广播全量 JSON / 文件轮询），
     * 任何一条失效（部分 ROM 上 SystemUI 收不到跨进程显式广播，或 SELinux 挡住文件读取）
     * 都表现为"改设置永远不生效"。本方法让每条通知在决策前<b>同步</b>重读一次文件，
     * 只要文件可读就必然用最新设置，不再依赖广播是否送达；文件读不到时靠广播通道。
     * 节流窗口与 App 端广播合并间隔一致（150ms），高频 hook 点不会放大读盘。</p>
     */
    public Settings getFresh() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastFreshRead >= FRESH_THROTTLE_MS) {
            mLastFreshRead = now;
            try {
                readAll();
            } catch (Throwable ignored) {
            }
        }
        return mSettings;
    }

    private static final long FRESH_THROTTLE_MS = 150;
    private long mLastFreshRead = -FRESH_THROTTLE_MS;

    /**
     * Returns the current settings. Cheap: reloads from disk only when the preference
     * file's revision / mtime changed (checked at most every 250 ms).
     */
    public Settings get() {
        long now = SystemClock.elapsedRealtime();
        if (now - mLastMtimeCheck >= MTIME_POLL_INTERVAL_MS) {
            mLastMtimeCheck = now;
            try {
                pollExternalChange();
            } catch (Throwable ignored) {
            }
        }
        return mSettings;
    }

    /**
     * 外部变更检测（自愈通道，广播丢失时兜底）。
     *
     * <p>旧实现只看文件 mtime：SystemUI（uid 1000）在很多 ROM 上读不到 App 私有目录的
     * {@code lastModified()}（返回 0），于是只在进程启动时读到一次设置，之后**永远不再刷新**
     * ——表现就是"改了样式必须重启系统才生效"。</p>
     *
     * <p>现在以 App 写入的 {@link #KEY_REVISION} 版本号为主判据（XSharedPreferences 能读到
     * 内容就一定能读到版本号），mtime 仅作兜底。</p>
     */
    private void pollExternalChange() {
        if (mPrefs == null) return;
        try {
            mPrefs.reload();
        } catch (Throwable ignored) {
        }
        long revision = -1;
        try {
            revision = mPrefs.getLong(KEY_REVISION, -1L);
        } catch (Throwable ignored) {
        }
        boolean changed = revision >= 0 && revision != mLastRevision;
        if (!changed) {
            try {
                File f = new File(PREFS_PATH);
                long mtime = f.lastModified();
                // mtime > 0 才算有效：读不到（权限）时返回 0，不能用它判定"未变化"
                if (mtime > 0 && mtime != mLastFileMtime) {
                    mLastFileMtime = mtime;
                    changed = true;
                }
            } catch (Throwable ignored) {
            }
        }
        if (changed) applyFromPrefs(revision);
    }

    private synchronized void readAll() {
        if (mPrefs == null) return;
        try {
            mPrefs.reload();
        } catch (Throwable ignored) {
        }
        long revision = -1;
        try {
            revision = mPrefs.getLong(KEY_REVISION, -1L);
        } catch (Throwable ignored) {
        }
        applyFromPrefs(revision);
    }

    private void applyFromPrefs(long revision) {
        Settings fresh;
        try {
            fresh = readSettings(mPrefs);
        } catch (Throwable t) {
            XposedBridge.log("HULFix: FAILED to read settings from prefs file: " + t);
            return;
        }
        if (revision >= 0) mLastRevision = revision;
        try {
            File f = new File(PREFS_PATH);
            long mtime = f.lastModified();
            if (mtime > 0) mLastFileMtime = mtime;
        } catch (Throwable ignored) {
        }
        applySettings(fresh, "file");
    }

    /**
     * 应用一份新设置并通知所有监听者。
     *
     * @param source 来源标识（broadcast-json / file / init），仅用于日志排查
     */
    private void applySettings(Settings fresh, String source) {
        mSettings = fresh;
        // 关键链路日志无条件输出（不受 debugLog 开关限制）："改设置不生效"类问题
        // 必须能从 Xposed 日志直接看到设置是否到达、来自哪条通道、值是什么
        XposedBridge.log("HULFix: settings applied (" + source + "), style="
                + fresh.styleId + " anchor=" + fresh.anchor
                + " offsetX=" + fresh.offsetXDp + " offsetY=" + fresh.offsetYDp
                + " enabled=" + fresh.enabled);
        for (Listener l : mListeners) {
            try {
                l.onSettingsReloaded(fresh);
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * 从任意 SharedPreferences 源（hook 进程的 XSharedPreferences，或 App 进程的
     * 真实 SharedPreferences）读取全部设置。键映射集中在此处，双端保持一致。
     */
    public static Settings readSettings(android.content.SharedPreferences p) {
        Settings s = new Settings();
        s.enabled = p.getBoolean("hulfix_enabled", s.enabled);
        s.styleId = p.getString("hulfix_style_id", s.styleId);
        s.customStylesJson = p.getString("hulfix_custom_styles", s.customStylesJson);
        s.themeMode = p.getInt("hulfix_theme_mode", s.themeMode);

        s.enterAnim = p.getInt("hulfix_enter_anim", s.enterAnim);
        s.exitAnim = p.getInt("hulfix_exit_anim", s.exitAnim);
        s.enterDir = p.getInt("hulfix_enter_dir", s.enterDir);
        s.enterDurationMs = p.getInt("hulfix_enter_duration", s.enterDurationMs);
        s.enterOvershoot = p.getFloat("hulfix_enter_overshoot", s.enterOvershoot);
        s.exitDurationMs = p.getInt("hulfix_exit_duration", s.exitDurationMs);
        s.staggerContent = p.getBoolean("hulfix_stagger", s.staggerContent);

        s.gestureHorizontal = p.getBoolean("hulfix_gesture_h", s.gestureHorizontal);
        s.gestureVertical = p.getBoolean("hulfix_gesture_v", s.gestureVertical);
        s.gesturePullDown = p.getBoolean("hulfix_gesture_pulldown", s.gesturePullDown);
        s.gestureTap = p.getBoolean("hulfix_gesture_tap", s.gestureTap);
        s.gestureLongPress = p.getBoolean("hulfix_gesture_longpress", s.gestureLongPress);
        s.swipeThresholdPx = p.getFloat("hulfix_swipe_threshold", s.swipeThresholdPx);
        s.flingVelocityPxS = p.getFloat("hulfix_fling_velocity", s.flingVelocityPxS);

        s.anchor = p.getInt("hulfix_anchor", s.anchor);
        s.offsetXDp = p.getInt("hulfix_offset_x", s.offsetXDp);
        s.offsetYDp = p.getInt("hulfix_offset_y", s.offsetYDp);
        s.widthScale = p.getFloat("hulfix_width_scale", s.widthScale);
        s.cornerScale = p.getFloat("hulfix_corner_scale", s.cornerScale);
        s.bgAlpha = p.getFloat("hulfix_bg_alpha", s.bgAlpha);

        s.lensEnabled = p.getBoolean("hulfix_lens_enabled", s.lensEnabled);
        s.lensThickness = p.getFloat("hulfix_lens_thickness", s.lensThickness);
        s.lensEdge = p.getFloat("hulfix_lens_edge", s.lensEdge);
        s.lensDispersion = p.getFloat("hulfix_lens_dispersion", s.lensDispersion);
        s.lensSpecular = p.getFloat("hulfix_lens_specular", s.lensSpecular);
        s.lensIOR = p.getFloat("hulfix_lens_ior", s.lensIOR);

        s.autoDismissMs = p.getInt("hulfix_auto_dismiss_ms", s.autoDismissMs);
        s.showAppName = p.getBoolean("hulfix_show_app_name", s.showAppName);
        s.maxTextLines = p.getInt("hulfix_max_text_lines", s.maxTextLines);

        s.filterMode = p.getInt("hulfix_filter_mode", s.filterMode);
        s.blockedApps = Settings.decodeSet(p.getString("hulfix_blocked_apps", ""));
        s.allowedApps = Settings.decodeSet(p.getString("hulfix_allowed_apps", ""));
        s.foregroundSuppressApps = Settings.decodeSet(p.getString("hulfix_fg_suppress_apps", ""));

        s.blockWhenDnd = p.getBoolean("hulfix_block_dnd", s.blockWhenDnd);
        s.blockWhenKeyguard = p.getBoolean("hulfix_block_keyguard", s.blockWhenKeyguard);
        s.blockWhenPanelExpanded = p.getBoolean("hulfix_block_panel", s.blockWhenPanelExpanded);
        s.blockWhenScreenOff = p.getBoolean("hulfix_block_screen_off", s.blockWhenScreenOff);
        s.blockWhenInCall = p.getBoolean("hulfix_block_incall", s.blockWhenInCall);
        s.skipOngoing = p.getBoolean("hulfix_skip_ongoing", s.skipOngoing);
        s.skipForegroundService = p.getBoolean("hulfix_skip_fgs", s.skipForegroundService);
        s.skipMedia = p.getBoolean("hulfix_skip_media", s.skipMedia);
        s.skipLowImportance = p.getBoolean("hulfix_skip_low_importance", s.skipLowImportance);

        s.engineEnabled = p.getBoolean("hulfix_engine_enabled", s.engineEnabled);
        s.globalCooldownMs = p.getInt("hulfix_global_cooldown_ms", s.globalCooldownMs);
        s.keyCooldownMs = p.getInt("hulfix_key_cooldown_ms", s.keyCooldownMs);
        s.appCooldownMs = p.getInt("hulfix_app_cooldown_ms", s.appCooldownMs);
        s.burstLimit = p.getInt("hulfix_burst_limit", s.burstLimit);
        s.burstWindowMs = p.getInt("hulfix_burst_window_ms", s.burstWindowMs);
        s.minuteLimit = p.getInt("hulfix_minute_limit", s.minuteLimit);
        s.userDismissCooldownMs = p.getInt("hulfix_user_dismiss_cooldown_ms", s.userDismissCooldownMs);
        s.userDismissScope = p.getInt("hulfix_user_dismiss_scope", s.userDismissScope);

        s.ledgerEnabled = p.getBoolean("hulfix_ledger_enabled", s.ledgerEnabled);
        s.dedupSameContent = p.getBoolean("hulfix_dedup_same_content", s.dedupSameContent);
        s.pauseUntilWallMs = p.getLong("hulfix_pause_until_wall_ms", s.pauseUntilWallMs);
        s.ignoredEntries = parseIgnored(p.getString("hulfix_ignored", "[]"));
        s.debugLog = p.getBoolean("hulfix_debug_log", s.debugLog);
        return s;
    }

    public static List<Settings.IgnoredEntry> parseIgnored(String json) {
        List<Settings.IgnoredEntry> out = new java.util.ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Settings.IgnoredEntry(
                        o.optString("key"), o.optString("pkg"),
                        o.optString("title"), o.optLong("at", 0)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }
}
