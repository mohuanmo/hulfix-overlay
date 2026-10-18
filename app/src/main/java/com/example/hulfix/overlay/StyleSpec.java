package com.example.hulfix.overlay;

import com.example.hulfix.core.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据驱动的悬浮窗样式规范。
 *
 * <p>四种内置样式与用户导入的自定义样式共用同一套描述：所有视觉参数（配色、圆角、描边、
 * 模糊、间距、字号、元素显隐、动画默认值）都是纯数据，因此可以 JSON 序列化 / 反序列化，
 * 由 {@link OverlayRenderer} 统一渲染。内置样式只是预置的 StyleSpec 常量。</p>
 */
public final class StyleSpec {

    // 变体：决定渲染器的绘制策略
    public static final String VARIANT_STANDARD = "standard"; // 标准安卓
    public static final String VARIANT_GLASS = "glass";       // M3 磨砂玻璃
    public static final String VARIANT_LIQUID = "liquid";     // iOS 液态玻璃
    public static final String VARIANT_NEON = "neon";         // 霓虹脉冲（自研）

    public static final int ACCENT_FIXED = 0;
    public static final int ACCENT_DYNAMIC = 1; // 从通知图标取色

    public String id = "custom";
    public String name = "自定义样式";
    public String author = "";
    public int version = 1;
    public String variant = VARIANT_GLASS;
    public boolean builtin = false;

    // 深色 / 浅色两套配色（ARGB int）
    public static final class Palette {
        public int[] bgColors = {0xF51C1C1E, 0xF51C1C1E}; // 2~3 个停止色
        public int textPrimary = 0xFFFFFFFF;
        public int textSecondary = 0xFFCCCCCC;
        public int appNameColor = 0xFF999999;
        public int accent = 0xFFA8C7FA;
        public int strokeColor = 0x30FFFFFF;
        public int highlightColor = 0x40FFFFFF; // 顶部高光
        public int shadowColor = 0x40000000;    // 底部接触阴影
        public int iconBgColor = 0x20A8C7FA;    // 图标容器底色
        public int iconStrokeColor = 0x60A8C7FA;
        public int ringColor = 0xFFA8C7FA;      // 倒计时进度环
        public int ringTrackColor = 0x30FFFFFF;
        public int pulseColor = 0xFFA8C7FA;
    }

    public Palette dark = new Palette();
    public Palette light = new Palette();

    // 尺寸（px 语义由渲染器结合密度换算；这里存 dp 值更直观）
    public float cornerRadiusDp = 28f;
    public float strokeWidthDp = 1f;
    /** 背景模糊半径 dp；>0 时在 API 31+ 启用 RenderEffect。 */
    public float blurRadiusDp = 0f;
    /** 背景之上叠加的纯色罩（用于玻璃压暗/提亮），0=不加罩。 */
    public int scrimColor = 0x00000000;
    public float paddingHDp = 20f;
    public float paddingVDp = 14f;
    public float iconSizeDp = 40f;
    public float iconPaddingDp = 6f;   // 图标容器内边距（glass/neon 圆形底）
    public float titleSizeSp = 15f;
    public float textSizeSp = 13f;
    public float appNameSizeSp = 11f;
    public float elevationDp = 8f;
    public float minHeightDp = 64f;

    // 元素显隐
    public boolean showIcon = true;
    public boolean showAppName = true;
    public boolean iconCircleBg = false;    // glass/neon：圆形 tonal 底
    public boolean progressRing = false;    // neon：倒计时进度环
    public boolean pulseDot = false;        // neon：呼吸脉冲点
    public boolean topHighlight = false;    // 顶部镜面高光
    public boolean bottomShadow = false;    // 底部接触阴影
    public boolean doubleStroke = false;    // liquid：双描边折射感
    public int maxTextLines = 2;

    // 强调色模式
    public int accentMode = ACCENT_FIXED;

    // 动画默认值（用户选择"跟随样式"时使用）
    public int enterAnim = Settings.ANIM_SLIDE;
    public int exitAnim = Settings.ANIM_SLIDE;
    public int enterDurationMs = 260;
    public int exitDurationMs = 240;
    public float enterOvershoot = 1.0f;

    // ==================== JSON ====================

    public static StyleSpec fromJson(String json) throws JSONException {
        return fromJson(new JSONObject(json));
    }

    public static StyleSpec fromJson(JSONObject o) throws JSONException {
        StyleSpec s = new StyleSpec();
        s.id = o.optString("id", s.id);
        s.name = o.optString("name", s.name);
        s.author = o.optString("author", s.author);
        s.version = o.optInt("version", 1);
        s.variant = normalizeVariant(o.optString("variant", s.variant));
        s.builtin = o.optBoolean("builtin", false);

        JSONObject d = o.optJSONObject("dark");
        if (d != null) s.dark = paletteFromJson(d, s.dark);
        JSONObject l = o.optJSONObject("light");
        if (l != null) s.light = paletteFromJson(l, s.light);

        s.cornerRadiusDp = (float) o.optDouble("cornerRadiusDp", s.cornerRadiusDp);
        s.strokeWidthDp = (float) o.optDouble("strokeWidthDp", s.strokeWidthDp);
        s.blurRadiusDp = (float) o.optDouble("blurRadiusDp", s.blurRadiusDp);
        s.scrimColor = parseColor(o.optString("scrimColor", null), s.scrimColor);
        s.paddingHDp = (float) o.optDouble("paddingHDp", s.paddingHDp);
        s.paddingVDp = (float) o.optDouble("paddingVDp", s.paddingVDp);
        s.iconSizeDp = (float) o.optDouble("iconSizeDp", s.iconSizeDp);
        s.iconPaddingDp = (float) o.optDouble("iconPaddingDp", s.iconPaddingDp);
        s.titleSizeSp = (float) o.optDouble("titleSizeSp", s.titleSizeSp);
        s.textSizeSp = (float) o.optDouble("textSizeSp", s.textSizeSp);
        s.appNameSizeSp = (float) o.optDouble("appNameSizeSp", s.appNameSizeSp);
        s.elevationDp = (float) o.optDouble("elevationDp", s.elevationDp);
        s.minHeightDp = (float) o.optDouble("minHeightDp", s.minHeightDp);

        s.showIcon = o.optBoolean("showIcon", s.showIcon);
        s.showAppName = o.optBoolean("showAppName", s.showAppName);
        s.iconCircleBg = o.optBoolean("iconCircleBg", s.iconCircleBg);
        s.progressRing = o.optBoolean("progressRing", s.progressRing);
        s.pulseDot = o.optBoolean("pulseDot", s.pulseDot);
        s.topHighlight = o.optBoolean("topHighlight", s.topHighlight);
        s.bottomShadow = o.optBoolean("bottomShadow", s.bottomShadow);
        s.doubleStroke = o.optBoolean("doubleStroke", s.doubleStroke);
        s.maxTextLines = o.optInt("maxTextLines", s.maxTextLines);
        s.accentMode = o.optInt("accentMode", s.accentMode);

        s.enterAnim = o.optInt("enterAnim", s.enterAnim);
        s.exitAnim = o.optInt("exitAnim", s.exitAnim);
        s.enterDurationMs = o.optInt("enterDurationMs", s.enterDurationMs);
        s.exitDurationMs = o.optInt("exitDurationMs", s.exitDurationMs);
        s.enterOvershoot = (float) o.optDouble("enterOvershoot", s.enterOvershoot);

        clamp(s);
        return s;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("author", author);
        o.put("version", version);
        o.put("variant", variant);
        o.put("builtin", builtin);
        o.put("dark", paletteToJson(dark));
        o.put("light", paletteToJson(light));
        o.put("cornerRadiusDp", cornerRadiusDp);
        o.put("strokeWidthDp", strokeWidthDp);
        o.put("blurRadiusDp", blurRadiusDp);
        o.put("scrimColor", colorHex(scrimColor));
        o.put("paddingHDp", paddingHDp);
        o.put("paddingVDp", paddingVDp);
        o.put("iconSizeDp", iconSizeDp);
        o.put("iconPaddingDp", iconPaddingDp);
        o.put("titleSizeSp", titleSizeSp);
        o.put("textSizeSp", textSizeSp);
        o.put("appNameSizeSp", appNameSizeSp);
        o.put("elevationDp", elevationDp);
        o.put("minHeightDp", minHeightDp);
        o.put("showIcon", showIcon);
        o.put("showAppName", showAppName);
        o.put("iconCircleBg", iconCircleBg);
        o.put("progressRing", progressRing);
        o.put("pulseDot", pulseDot);
        o.put("topHighlight", topHighlight);
        o.put("bottomShadow", bottomShadow);
        o.put("doubleStroke", doubleStroke);
        o.put("maxTextLines", maxTextLines);
        o.put("accentMode", accentMode);
        o.put("enterAnim", enterAnim);
        o.put("exitAnim", exitAnim);
        o.put("enterDurationMs", enterDurationMs);
        o.put("exitDurationMs", exitDurationMs);
        o.put("enterOvershoot", enterOvershoot);
        return o;
    }

    /** 解析导入文件：支持顶层数组 [{...}]、单个样式对象 {...}、{"styles":[...]} 包装三种形态。 */
    public static List<StyleSpec> listFromJson(String json) throws JSONException {
        List<StyleSpec> out = new ArrayList<>();
        if (json == null) return out;
        String trimmed = json.trim();
        if (trimmed.startsWith("[")) {
            JSONArray arr = new JSONArray(trimmed);
            for (int i = 0; i < arr.length(); i++) {
                out.add(fromJson(arr.getJSONObject(i)));
            }
            return out;
        }
        JSONObject root = new JSONObject(trimmed);
        JSONArray arr = root.optJSONArray("styles");
        if (arr == null) {
            out.add(fromJson(root));
        } else {
            for (int i = 0; i < arr.length(); i++) {
                out.add(fromJson(arr.getJSONObject(i)));
            }
        }
        return out;
    }

    // ==================== 工具 ====================

    /**
     * 视觉内容哈希：配色 / 几何 / 显隐开关的全量指纹。
     *
     * <p>用途：{@code OverlayController} 的视图池复用判定。旧版 poolKey 只含几何参数，
     * 于是"重新导入一个只改了配色的自定义样式"时复用键不变 → 一直复用旧视图树，
     * 新颜色永远不生效（直到几何参数恰好变化才重建）。本哈希把一切视觉参数纳入。</p>
     *
     * <p><b>必须走 {@code toJson().toString().hashCode()}</b>：{@code String.hashCode}
     * 由 Java 规范确定，跨进程（App / SystemUI）取值一致；而
     * {@code JSONObject.hashCode()} 是实现定义的——Android 的 {@code android.org.json}
     * 继承 {@code HashMap}（内容哈希），桌面 org.json 不重写（身份哈希），
     * 两者取值规则不同，直接拿它做指纹在 JVM 单测与真机上行为会分叉。</p>
     */
    public int contentHash() {
        try {
            return toJson().toString().hashCode();
        } catch (JSONException e) {
            return 0;
        }
    }

    public static String normalizeVariant(String v) {
        if (VARIANT_STANDARD.equals(v) || VARIANT_GLASS.equals(v)
                || VARIANT_LIQUID.equals(v) || VARIANT_NEON.equals(v)) {
            return v;
        }
        return VARIANT_GLASS;
    }

    /** 把导入/编辑后的数值夹到合理范围，防止 JSON 注入导致渲染异常。 */
    public static void clamp(StyleSpec s) {
        s.cornerRadiusDp = clampf(s.cornerRadiusDp, 0f, 48f);
        s.strokeWidthDp = clampf(s.strokeWidthDp, 0f, 4f);
        s.blurRadiusDp = clampf(s.blurRadiusDp, 0f, 60f);
        s.paddingHDp = clampf(s.paddingHDp, 0f, 64f);
        s.paddingVDp = clampf(s.paddingVDp, 0f, 48f);
        s.iconSizeDp = clampf(s.iconSizeDp, 16f, 96f);
        s.iconPaddingDp = clampf(s.iconPaddingDp, 0f, 24f);
        s.titleSizeSp = clampf(s.titleSizeSp, 8f, 32f);
        s.textSizeSp = clampf(s.textSizeSp, 8f, 28f);
        s.appNameSizeSp = clampf(s.appNameSizeSp, 6f, 20f);
        s.elevationDp = clampf(s.elevationDp, 0f, 32f);
        s.minHeightDp = clampf(s.minHeightDp, 32f, 200f);
        s.maxTextLines = Math.max(1, Math.min(5, s.maxTextLines));
        s.enterDurationMs = Math.max(60, Math.min(1500, s.enterDurationMs));
        s.exitDurationMs = Math.max(60, Math.min(1500, s.exitDurationMs));
        s.enterOvershoot = clampf(s.enterOvershoot, 0f, 3f);
        if (s.dark.bgColors == null || s.dark.bgColors.length < 2) {
            s.dark.bgColors = new int[]{0xF51C1C1E, 0xF51C1C1E};
        }
        if (s.light.bgColors == null || s.light.bgColors.length < 2) {
            s.light.bgColors = new int[]{0xF2FFFFFF, 0xF2FFFFFF};
        }
    }

    private static float clampf(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static int parseColor(String hex, int fallback) {
        if (hex == null || hex.isEmpty()) return fallback;
        try {
            String h = hex.trim();
            if (h.startsWith("#")) h = h.substring(1);
            if (h.length() == 6) h = "FF" + h;
            if (h.length() != 8) return fallback;
            return (int) Long.parseLong(h, 16);
        } catch (Exception e) {
            return fallback;
        }
    }

    public static String colorHex(int argb) {
        return String.format("#%08X", argb);
    }

    private static Palette paletteFromJson(JSONObject o, Palette def) throws JSONException {
        Palette p = new Palette();
        p.bgColors = colorArrayFromJson(o.optJSONArray("bgColors"), def.bgColors);
        p.textPrimary = parseColor(o.optString("textPrimary", null), def.textPrimary);
        p.textSecondary = parseColor(o.optString("textSecondary", null), def.textSecondary);
        p.appNameColor = parseColor(o.optString("appNameColor", null), def.appNameColor);
        p.accent = parseColor(o.optString("accent", null), def.accent);
        p.strokeColor = parseColor(o.optString("strokeColor", null), def.strokeColor);
        p.highlightColor = parseColor(o.optString("highlightColor", null), def.highlightColor);
        p.shadowColor = parseColor(o.optString("shadowColor", null), def.shadowColor);
        p.iconBgColor = parseColor(o.optString("iconBgColor", null), def.iconBgColor);
        p.iconStrokeColor = parseColor(o.optString("iconStrokeColor", null), def.iconStrokeColor);
        p.ringColor = parseColor(o.optString("ringColor", null), def.ringColor);
        p.ringTrackColor = parseColor(o.optString("ringTrackColor", null), def.ringTrackColor);
        p.pulseColor = parseColor(o.optString("pulseColor", null), def.pulseColor);
        return p;
    }

    private static JSONObject paletteToJson(Palette p) throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray bg = new JSONArray();
        for (int c : p.bgColors) bg.put(colorHex(c));
        o.put("bgColors", bg);
        o.put("textPrimary", colorHex(p.textPrimary));
        o.put("textSecondary", colorHex(p.textSecondary));
        o.put("appNameColor", colorHex(p.appNameColor));
        o.put("accent", colorHex(p.accent));
        o.put("strokeColor", colorHex(p.strokeColor));
        o.put("highlightColor", colorHex(p.highlightColor));
        o.put("shadowColor", colorHex(p.shadowColor));
        o.put("iconBgColor", colorHex(p.iconBgColor));
        o.put("iconStrokeColor", colorHex(p.iconStrokeColor));
        o.put("ringColor", colorHex(p.ringColor));
        o.put("ringTrackColor", colorHex(p.ringTrackColor));
        o.put("pulseColor", colorHex(p.pulseColor));
        return o;
    }

    private static int[] colorArrayFromJson(JSONArray arr, int[] def) {
        if (arr == null || arr.length() < 2) return def;
        int[] out = new int[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            out[i] = parseColor(arr.optString(i, null), def[Math.min(i, def.length - 1)]);
        }
        return out;
    }
}
