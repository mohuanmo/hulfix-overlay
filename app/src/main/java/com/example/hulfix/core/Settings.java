package com.example.hulfix.core;

import com.example.hulfix.overlay.StyleRegistry.StyleIds;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Strongly typed view of every user-configurable option of HULFix.
 *
 * <p>Defaults intentionally mirror the previous hard-coded constants so that a fresh install
 * behaves exactly like the old build until the user changes something.</p>
 *
 * <p>Pure data holder: no Android framework calls, so it stays unit-test friendly. Instances are
 * created by {@link SettingsRepository} and handed to the engine / overlay layers.</p>
 */
public final class Settings {

    // ==================== 模块总开关 ====================
    public boolean enabled = true;

    // ==================== 样式 ====================
    public String styleId = StyleIds.M3E_GLASS;
    /** JSON array of imported custom StyleSpec objects. */
    public String customStylesJson = "[]";
    /**
     * 逐样式微调补丁：{@code {styleId: {partial StyleSpec JSON}}}。
     *
     * <p>4 个内置样式是模板；用户对某样式的调整只存补丁（未出现的键保持模板原值），
     * 由 {@code StyleRegistry.byId(id, custom, overrides)} 解析时叠加。
     * 空对象 = 全部使用出厂模板。</p>
     */
    public String styleOverridesJson = "{}";
    public static final int THEME_AUTO = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;
    public int themeMode = THEME_AUTO;

    // ==================== 动画 ====================
    public static final int ANIM_FOLLOW_STYLE = 0; // 跟随样式默认
    public static final int ANIM_SLIDE = 1;
    public static final int ANIM_SCALE = 2;
    public static final int ANIM_SPRING = 3;
    public static final int ANIM_FADE = 4;
    public int enterAnim = ANIM_FOLLOW_STYLE;
    public int exitAnim = ANIM_FOLLOW_STYLE;

    public static final int DIR_AUTO = 0;
    public static final int DIR_TOP = 1;
    public static final int DIR_BOTTOM = 2;
    public static final int DIR_LEFT = 3;
    public static final int DIR_RIGHT = 4;
    public int enterDir = DIR_AUTO;

    public int enterDurationMs = 260;   // 原为 300ms，略提速
    public float enterOvershoot = 1.0f; // 弹性强度
    public int exitDurationMs = 240;    // 原为 250ms
    public boolean staggerContent = true; // 内容错落入场（图标→标题→正文）

    // ==================== 手势 ====================
    public boolean gestureHorizontal = true;  // 左右滑消失
    public boolean gestureVertical = true;    // 上下滑
    public boolean gesturePullDown = true;    // 下拉展开通知栏
    public boolean gestureTap = true;         // 点击打开通知
    public boolean gestureLongPress = false;  // 长按忽略
    public float swipeThresholdPx = 140f;     // 原 SWIPE_DESTROY_THRESHOLD
    public float flingVelocityPxS = 200f;     // 原 MIN_FLING_VELOCITY

    // ==================== 位置与尺寸 ====================
    // 9 宫格锚点，与原硬编码 WIN_X/WIN_Y 对应的默认值 = 右上
    public static final int ANCHOR_TOP_LEFT = 0;
    public static final int ANCHOR_TOP_CENTER = 1;
    public static final int ANCHOR_TOP_RIGHT = 2;
    public static final int ANCHOR_MID_LEFT = 3;
    public static final int ANCHOR_CENTER = 4;
    public static final int ANCHOR_MID_RIGHT = 5;
    public static final int ANCHOR_BOTTOM_LEFT = 6;
    public static final int ANCHOR_BOTTOM_CENTER = 7;
    public static final int ANCHOR_BOTTOM_RIGHT = 8;
    public int anchor = ANCHOR_TOP_RIGHT;

    public int offsetXDp = 0;
    public int offsetYDp = 0;
    /** 宽度相对样式默认值的缩放。 */
    public float widthScale = 1.0f;
    /** 圆角相对样式默认值的缩放。 */
    public float cornerScale = 1.0f;
    /** 背景不透明度相对样式默认值的缩放。 */
    public float bgAlpha = 1.0f;

    // ==================== AGSL 真折射引擎 ====================
    // 对液态玻璃（liquid）类样式启用物理折射：SDF 法线 + Snell 定律 + 色散 + 菲涅尔高光。
    // API < 33 或驱动不兼容时由渲染层自动降级为传统模拟效果（模糊 + 双描边 + 高光）。
    /** 真折射引擎总开关（仅影响 liquid 类样式）。 */
    public boolean lensEnabled = true;
    /** 折射位移倍率（px）：背景像素被拉扯的幅度，0 = 平面玻璃无折射。 */
    public float lensThickness = 14f;
    /** 边缘透镜增幅 0..1：圆角边缘处法线倾斜的强度（玻璃边缘更厚的视觉暗示）。 */
    public float lensEdge = 0.75f;
    /** 色散强度 0..1：RGB 三通道折射率差（边缘彩虹效果）。 */
    public float lensDispersion = 0.35f;
    /** 菲涅尔镜面高光 0..2。 */
    public float lensSpecular = 1.0f;
    /** 折射率 IOR：1.0 = 空气（无折射），1.5 = 普通玻璃，2.0 = 钻石。 */
    public float lensIOR = 1.5f;

    // ==================== 动态光效引擎（实时渲染） ====================
    // 在静态折射层之上逐帧重算：方向性镜面高光（光源随触摸/手势滑动）、
    // 自适应接触阴影（背离光源侧变暗）、流动光泽（相位持续推进）、
    // 环境光适应玻璃后方内容的亮度。交互（按压/滑动/消息弹出）注入能量并衰减。
    /** 动态光效引擎总开关（关闭 = 纯静态折射，零常驻开销）。 */
    public boolean lensDynamic = true;
    /** 动态强度 0..1：0 = 完全静态设计值，1 = 满额动态光效。 */
    public float lensDynamicStrength = 1.0f;
    /** 自适应接触阴影强度 0..1。 */
    public float lensAdaptiveShadow = 0.35f;
    /** 高光色跟随系统强调色（Material You 动态取色）。 */
    public boolean lensAccentFollow = false;

    // ==================== 显示行为 ====================
    public int autoDismissMs = 6000;      // 原 AUTO_DISMISS_MS
    public boolean showAppName = true;
    public int maxTextLines = 2;

    // ==================== 应用过滤 ====================
    public static final int FILTER_BLACKLIST = 0; // 全部允许，命中黑名单不弹
    public static final int FILTER_WHITELIST = 1; // 仅白名单允许
    public int filterMode = FILTER_BLACKLIST;
    public Set<String> blockedApps = new HashSet<>();
    public Set<String> allowedApps = new HashSet<>();
    /** 这些应用处于前台时抑制弹窗。 */
    public Set<String> foregroundSuppressApps = new HashSet<>();

    // ==================== 场景检测 ====================
    public boolean blockWhenDnd = true;
    public boolean blockWhenKeyguard = true;
    public boolean blockWhenPanelExpanded = true;
    public boolean blockWhenScreenOff = true;
    public boolean blockWhenInCall = true;
    public boolean skipOngoing = true;
    public boolean skipForegroundService = true;
    public boolean skipMedia = false;         // 跳过带媒体会话的通知
    public boolean skipLowImportance = false; // 仅弹 important 及以上频道

    // ==================== 冷却引擎 ====================
    public boolean engineEnabled = true;
    public int globalCooldownMs = 1000;      // 原 GLOBAL_COOLDOWN_MS
    /** 同一通知 key 两次弹出的最小间隔（0=不限）。 */
    public int keyCooldownMs = 3000;
    /** 同一应用两次弹出的最小间隔（0=不限）。 */
    public int appCooldownMs = 0;
    /** 突发限流：窗口内最大弹出次数（0=不限）。 */
    public int burstLimit = 3;
    public int burstWindowMs = 10000;
    /** 每分钟最大弹出次数（0=不限）。 */
    public int minuteLimit = 0;
    /** 用户划掉后的冷却时长。 */
    public int userDismissCooldownMs = 60000; // 原 USER_IGNORE_COOLDOWN_MS=500 太短，改为 60s 可调
    public static final int SCOPE_KEY = 0;
    public static final int SCOPE_APP = 1;
    public static final int SCOPE_GLOBAL = 2;
    public int userDismissScope = SCOPE_KEY;

    // ==================== 消息台账（去重） ====================
    public boolean ledgerEnabled = true;
    /** 同 key 同内容且在展示保鲜期内 → 直接抑制（多 hook 点重复触发的核心防线）。 */
    public boolean dedupSameContent = true;

    /** 手动暂停截止时间（wall clock，System.currentTimeMillis）。 */
    public long pauseUntilWallMs = 0;
    /** 用户忽略的通知（跨重启持久化）。 */
    public List<IgnoredEntry> ignoredEntries = new ArrayList<>();

    // ==================== 调试 ====================
    public boolean debugLog = false;

    /** 用户划掉/忽略的通知记录（持久化于 prefs）。 */
    public static final class IgnoredEntry {
        public String key;
        public String pkg;
        public String title;
        public long atWallMs;

        public IgnoredEntry() {
        }

        public IgnoredEntry(String key, String pkg, String title, long atWallMs) {
            this.key = key;
            this.pkg = pkg;
            this.title = title;
            this.atWallMs = atWallMs;
        }
    }

    /**
     * 浅拷贝（经 {@link #toJson()} / {@link #fromJson(String)} 往返，天然与字段表同步）。
     *
     * <p>用途：设置页预览需要"只改一个开关"的副本而不影响原对象（例如迷你预览
     * 临时关掉真折射）。失败时返回全新默认实例（防御，不返回 null）。</p>
     */
    public Settings copy() {
        Settings s = fromJson(toJson());
        return s != null ? s : new Settings();
    }

    // ---------- 集合字段的编解码（SharedPreferences 不存 Set<String>，XSP 的 StringSet 亦不可靠） ----------

    public static String encodeSet(Set<String> set) {
        if (set == null || set.isEmpty()) return "";
        return String.join(",", set);
    }

    public static Set<String> decodeSet(String raw) {
        Set<String> out = new HashSet<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String s : raw.split(",")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    // ==================== JSON 序列化（设置热更新广播通道） ====================

    /**
     * 全量设置的 JSON 快照。
     *
     * <p><b>为什么需要它</b>：hook 进程（SystemUI，system_app 域）在很多 ROM 上被 SELinux
     * 挡住，读不到 App 私有目录的 prefs 文件——XSharedPreferences 与文件 mtime 两条
     * 老通道都不可靠，表现为"改任何设置都不生效"。现在 App 每次写设置后由广播直接
     * 携带本 JSON（{@code ACTION_SETTINGS_CHANGED} 的 {@code EXTRA_SETTINGS_JSON}），
     * hook 端收到即用，完全不依赖文件读取。</p>
     *
     * <p>键名与 SharedPreferences 键一致，便于对照排查。</p>
     */
    public String toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("hulfix_enabled", enabled);
            o.put("hulfix_style_id", styleId);
            o.put("hulfix_custom_styles", customStylesJson);
            o.put("hulfix_style_overrides", styleOverridesJson);
            o.put("hulfix_theme_mode", themeMode);

            o.put("hulfix_enter_anim", enterAnim);
            o.put("hulfix_exit_anim", exitAnim);
            o.put("hulfix_enter_dir", enterDir);
            o.put("hulfix_enter_duration", enterDurationMs);
            o.put("hulfix_enter_overshoot", (double) enterOvershoot);
            o.put("hulfix_exit_duration", exitDurationMs);
            o.put("hulfix_stagger", staggerContent);

            o.put("hulfix_gesture_h", gestureHorizontal);
            o.put("hulfix_gesture_v", gestureVertical);
            o.put("hulfix_gesture_pulldown", gesturePullDown);
            o.put("hulfix_gesture_tap", gestureTap);
            o.put("hulfix_gesture_longpress", gestureLongPress);
            o.put("hulfix_swipe_threshold", (double) swipeThresholdPx);
            o.put("hulfix_fling_velocity", (double) flingVelocityPxS);

            o.put("hulfix_anchor", anchor);
            o.put("hulfix_offset_x", offsetXDp);
            o.put("hulfix_offset_y", offsetYDp);
            o.put("hulfix_width_scale", (double) widthScale);
            o.put("hulfix_corner_scale", (double) cornerScale);
            o.put("hulfix_bg_alpha", (double) bgAlpha);

            o.put("hulfix_lens_enabled", lensEnabled);
            o.put("hulfix_lens_thickness", (double) lensThickness);
            o.put("hulfix_lens_edge", (double) lensEdge);
            o.put("hulfix_lens_dispersion", (double) lensDispersion);
            o.put("hulfix_lens_specular", (double) lensSpecular);
            o.put("hulfix_lens_ior", (double) lensIOR);
            o.put("hulfix_lens_dynamic", lensDynamic);
            o.put("hulfix_lens_dynamic_strength", (double) lensDynamicStrength);
            o.put("hulfix_lens_adaptive_shadow", (double) lensAdaptiveShadow);
            o.put("hulfix_lens_accent_follow", lensAccentFollow);

            o.put("hulfix_auto_dismiss_ms", autoDismissMs);
            o.put("hulfix_show_app_name", showAppName);
            o.put("hulfix_max_text_lines", maxTextLines);

            o.put("hulfix_filter_mode", filterMode);
            o.put("hulfix_blocked_apps", encodeSet(blockedApps));
            o.put("hulfix_allowed_apps", encodeSet(allowedApps));
            o.put("hulfix_fg_suppress_apps", encodeSet(foregroundSuppressApps));

            o.put("hulfix_block_dnd", blockWhenDnd);
            o.put("hulfix_block_keyguard", blockWhenKeyguard);
            o.put("hulfix_block_panel", blockWhenPanelExpanded);
            o.put("hulfix_block_screen_off", blockWhenScreenOff);
            o.put("hulfix_block_incall", blockWhenInCall);
            o.put("hulfix_skip_ongoing", skipOngoing);
            o.put("hulfix_skip_fgs", skipForegroundService);
            o.put("hulfix_skip_media", skipMedia);
            o.put("hulfix_skip_low_importance", skipLowImportance);

            o.put("hulfix_engine_enabled", engineEnabled);
            o.put("hulfix_global_cooldown_ms", globalCooldownMs);
            o.put("hulfix_key_cooldown_ms", keyCooldownMs);
            o.put("hulfix_app_cooldown_ms", appCooldownMs);
            o.put("hulfix_burst_limit", burstLimit);
            o.put("hulfix_burst_window_ms", burstWindowMs);
            o.put("hulfix_minute_limit", minuteLimit);
            o.put("hulfix_user_dismiss_cooldown_ms", userDismissCooldownMs);
            o.put("hulfix_user_dismiss_scope", userDismissScope);

            o.put("hulfix_ledger_enabled", ledgerEnabled);
            o.put("hulfix_dedup_same_content", dedupSameContent);
            o.put("hulfix_pause_until_wall_ms", pauseUntilWallMs);

            JSONArray ignored = new JSONArray();
            for (IgnoredEntry e : ignoredEntries) {
                if (e == null || e.key == null) continue;
                JSONObject io = new JSONObject();
                io.put("key", e.key);
                io.put("pkg", e.pkg == null ? "" : e.pkg);
                io.put("title", e.title == null ? "" : e.title);
                io.put("at", e.atWallMs);
                ignored.put(io);
            }
            o.put("hulfix_ignored", ignored.toString());

            o.put("hulfix_debug_log", debugLog);
        } catch (JSONException ignored) {
        }
        return o.toString();
    }

    /** 从 {@link #toJson()} 的 JSON 还原设置；解析失败返回 null（调用方保留旧值）。 */
    public static Settings fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return fromJson(new JSONObject(json));
        } catch (JSONException e) {
            return null;
        }
    }

    public static Settings fromJson(JSONObject o) {
        Settings s = new Settings();
        s.enabled = o.optBoolean("hulfix_enabled", s.enabled);
        s.styleId = o.optString("hulfix_style_id", s.styleId);
        s.customStylesJson = o.optString("hulfix_custom_styles", s.customStylesJson);
        s.styleOverridesJson = o.optString("hulfix_style_overrides", s.styleOverridesJson);
        s.themeMode = o.optInt("hulfix_theme_mode", s.themeMode);

        s.enterAnim = o.optInt("hulfix_enter_anim", s.enterAnim);
        s.exitAnim = o.optInt("hulfix_exit_anim", s.exitAnim);
        s.enterDir = o.optInt("hulfix_enter_dir", s.enterDir);
        s.enterDurationMs = o.optInt("hulfix_enter_duration", s.enterDurationMs);
        s.enterOvershoot = (float) o.optDouble("hulfix_enter_overshoot", s.enterOvershoot);
        s.exitDurationMs = o.optInt("hulfix_exit_duration", s.exitDurationMs);
        s.staggerContent = o.optBoolean("hulfix_stagger", s.staggerContent);

        s.gestureHorizontal = o.optBoolean("hulfix_gesture_h", s.gestureHorizontal);
        s.gestureVertical = o.optBoolean("hulfix_gesture_v", s.gestureVertical);
        s.gesturePullDown = o.optBoolean("hulfix_gesture_pulldown", s.gesturePullDown);
        s.gestureTap = o.optBoolean("hulfix_gesture_tap", s.gestureTap);
        s.gestureLongPress = o.optBoolean("hulfix_gesture_longpress", s.gestureLongPress);
        s.swipeThresholdPx = (float) o.optDouble("hulfix_swipe_threshold", s.swipeThresholdPx);
        s.flingVelocityPxS = (float) o.optDouble("hulfix_fling_velocity", s.flingVelocityPxS);

        s.anchor = o.optInt("hulfix_anchor", s.anchor);
        s.offsetXDp = o.optInt("hulfix_offset_x", s.offsetXDp);
        s.offsetYDp = o.optInt("hulfix_offset_y", s.offsetYDp);
        s.widthScale = (float) o.optDouble("hulfix_width_scale", s.widthScale);
        s.cornerScale = (float) o.optDouble("hulfix_corner_scale", s.cornerScale);
        s.bgAlpha = (float) o.optDouble("hulfix_bg_alpha", s.bgAlpha);

        s.lensEnabled = o.optBoolean("hulfix_lens_enabled", s.lensEnabled);
        s.lensThickness = (float) o.optDouble("hulfix_lens_thickness", s.lensThickness);
        s.lensEdge = (float) o.optDouble("hulfix_lens_edge", s.lensEdge);
        s.lensDispersion = (float) o.optDouble("hulfix_lens_dispersion", s.lensDispersion);
        s.lensSpecular = (float) o.optDouble("hulfix_lens_specular", s.lensSpecular);
        s.lensIOR = (float) o.optDouble("hulfix_lens_ior", s.lensIOR);
        s.lensDynamic = o.optBoolean("hulfix_lens_dynamic", s.lensDynamic);
        s.lensDynamicStrength = (float) o.optDouble("hulfix_lens_dynamic_strength", s.lensDynamicStrength);
        s.lensAdaptiveShadow = (float) o.optDouble("hulfix_lens_adaptive_shadow", s.lensAdaptiveShadow);
        s.lensAccentFollow = o.optBoolean("hulfix_lens_accent_follow", s.lensAccentFollow);

        s.autoDismissMs = o.optInt("hulfix_auto_dismiss_ms", s.autoDismissMs);
        s.showAppName = o.optBoolean("hulfix_show_app_name", s.showAppName);
        s.maxTextLines = o.optInt("hulfix_max_text_lines", s.maxTextLines);

        s.filterMode = o.optInt("hulfix_filter_mode", s.filterMode);
        s.blockedApps = decodeSet(o.optString("hulfix_blocked_apps", ""));
        s.allowedApps = decodeSet(o.optString("hulfix_allowed_apps", ""));
        s.foregroundSuppressApps = decodeSet(o.optString("hulfix_fg_suppress_apps", ""));

        s.blockWhenDnd = o.optBoolean("hulfix_block_dnd", s.blockWhenDnd);
        s.blockWhenKeyguard = o.optBoolean("hulfix_block_keyguard", s.blockWhenKeyguard);
        s.blockWhenPanelExpanded = o.optBoolean("hulfix_block_panel", s.blockWhenPanelExpanded);
        s.blockWhenScreenOff = o.optBoolean("hulfix_block_screen_off", s.blockWhenScreenOff);
        s.blockWhenInCall = o.optBoolean("hulfix_block_incall", s.blockWhenInCall);
        s.skipOngoing = o.optBoolean("hulfix_skip_ongoing", s.skipOngoing);
        s.skipForegroundService = o.optBoolean("hulfix_skip_fgs", s.skipForegroundService);
        s.skipMedia = o.optBoolean("hulfix_skip_media", s.skipMedia);
        s.skipLowImportance = o.optBoolean("hulfix_skip_low_importance", s.skipLowImportance);

        s.engineEnabled = o.optBoolean("hulfix_engine_enabled", s.engineEnabled);
        s.globalCooldownMs = o.optInt("hulfix_global_cooldown_ms", s.globalCooldownMs);
        s.keyCooldownMs = o.optInt("hulfix_key_cooldown_ms", s.keyCooldownMs);
        s.appCooldownMs = o.optInt("hulfix_app_cooldown_ms", s.appCooldownMs);
        s.burstLimit = o.optInt("hulfix_burst_limit", s.burstLimit);
        s.burstWindowMs = o.optInt("hulfix_burst_window_ms", s.burstWindowMs);
        s.minuteLimit = o.optInt("hulfix_minute_limit", s.minuteLimit);
        s.userDismissCooldownMs = o.optInt("hulfix_user_dismiss_cooldown_ms", s.userDismissCooldownMs);
        s.userDismissScope = o.optInt("hulfix_user_dismiss_scope", s.userDismissScope);

        s.ledgerEnabled = o.optBoolean("hulfix_ledger_enabled", s.ledgerEnabled);
        s.dedupSameContent = o.optBoolean("hulfix_dedup_same_content", s.dedupSameContent);
        s.pauseUntilWallMs = o.optLong("hulfix_pause_until_wall_ms", s.pauseUntilWallMs);
        s.ignoredEntries = parseIgnored(o.optString("hulfix_ignored", "[]"));
        s.debugLog = o.optBoolean("hulfix_debug_log", s.debugLog);
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

    public static final List<Integer> ANCHOR_ORDER = Collections.unmodifiableList(Arrays.asList(
            ANCHOR_TOP_LEFT, ANCHOR_TOP_CENTER, ANCHOR_TOP_RIGHT,
            ANCHOR_MID_LEFT, ANCHOR_CENTER, ANCHOR_MID_RIGHT,
            ANCHOR_BOTTOM_LEFT, ANCHOR_BOTTOM_CENTER, ANCHOR_BOTTOM_RIGHT));

    /** 该锚点是否位于屏幕右半区（用于推导滑入方向）。 */
    public boolean anchorIsRight() {
        return anchor == ANCHOR_TOP_RIGHT || anchor == ANCHOR_MID_RIGHT || anchor == ANCHOR_BOTTOM_RIGHT;
    }

    /** 该锚点是否位于屏幕下半区。 */
    public boolean anchorIsBottom() {
        return anchor == ANCHOR_BOTTOM_LEFT || anchor == ANCHOR_BOTTOM_CENTER || anchor == ANCHOR_BOTTOM_RIGHT;
    }

    /** 该锚点是否位于水平中点列。 */
    public boolean anchorIsCenterX() {
        return anchor == ANCHOR_TOP_CENTER || anchor == ANCHOR_CENTER || anchor == ANCHOR_BOTTOM_CENTER;
    }

    /** 该锚点是否位于垂直中点行。 */
    public boolean anchorIsCenterY() {
        return anchor == ANCHOR_MID_LEFT || anchor == ANCHOR_CENTER || anchor == ANCHOR_MID_RIGHT;
    }
}
