package com.example.hulfix.ui;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.example.hulfix.core.Settings;
import com.example.hulfix.overlay.StyleRegistry;
import com.example.hulfix.overlay.StyleSpec;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * 「调整当前样式」面板：对<b>当前选中的样式</b>做逐样式微调（模板 + 调整层模型）。
 *
 * <p>4 个内置样式是只读模板；用户调整不写回模板，而是以补丁形式存入设置的
 * {@code hulfix_style_overrides}（{@code {styleId: {partial JSON}}}），由
 * {@link StyleRegistry#byId(String, String, String)} 解析时叠加。因此：</p>
 * <ul>
 *   <li>选中哪个样式，面板就调整哪个（点选样式即切换面板绑定）；</li>
 *   <li>补丁只含被改过的键，模板后续演进的其余参数不会被冻结；</li>
 *   <li>一键"重置调整"即删除补丁，回到出厂模板。</li>
 * </ul>
 *
 * <p>全部改动即时写盘（经 {@link SettingsStore} 广播热更新），并回调
 * {@code onPreviewRefresh} 驱动大预览实时重渲染——所见即所得。</p>
 */
public final class StyleAdjustPanel {

    private final Context mCtx;
    private final SettingsStore mStore;
    private final LinearLayout mContainer;
    private final Runnable mOnPreviewRefresh;
    /**
     * 预览刷新节流：几何 / 颜色滑块每 tick 都改 spec 指纹，直接刷新意味着
     * 每次 tick 重建整棵预览视图树 + 重编译一次 AGSL 着色器（毫秒级）。
     * 合并到 60ms 窗口内一次，拖动依然实时（所见即所得），但不放大重编译。
     */
    private final android.os.Handler mUiHandler;
    private final Runnable mCoalescedRefresh;

    /** 当前绑定的样式 id；null = 尚未绑定。 */
    private String mStyleId;
    /** 自定义样式 JSON（bind 时快照：自定义样式的基底定义来自导入数据）。 */
    private String mCustomStylesJson = "[]";
    /** 模板 + 补丁后的生效样式（控件初值来源）。 */
    private StyleSpec mEffective;
    /** 内存补丁（每次改动立即写盘）。 */
    private JSONObject mPatch;
    /** 配色编辑目标：true = 深色调色板，false = 浅色。 */
    private boolean mEditDark = true;

    public StyleAdjustPanel(Context ctx, SettingsStore store, LinearLayout container,
                            Runnable onPreviewRefresh) {
        mCtx = ctx;
        mStore = store;
        mContainer = container;
        mOnPreviewRefresh = onPreviewRefresh;
        // 节流器在构造末期创建（此刻 final 字段均已赋值）
        mUiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        mCoalescedRefresh = () -> {
            if (mOnPreviewRefresh != null) mOnPreviewRefresh.run();
        };
    }

    /** 选中样式变化时重建面板（样式切换 / 页面重建）。 */
    public void bind(String styleId, Settings settings) {
        if (styleId == null) styleId = StyleRegistry.StyleIds.M3E_GLASS;
        if (styleId.equals(mStyleId) && mPatch != null) return;
        mStyleId = styleId;
        mCustomStylesJson = settings.customStylesJson != null
                ? settings.customStylesJson : "[]";
        mEffective = StyleRegistry.byId(styleId, mCustomStylesJson,
                settings.styleOverridesJson);
        JSONObject patch = StyleRegistry.overridePatch(settings.styleOverridesJson, styleId);
        try {
            mPatch = patch != null ? new JSONObject(patch.toString()) : new JSONObject();
        } catch (JSONException e) {
            mPatch = new JSONObject();
        }
        mEditDark = isDarkMode(settings);
        build();
    }

    /** 强制重建（外部合并了补丁后调用：同一样式也要按最新补丁重建控件初值）。 */
    public void rebind(Settings settings) {
        mStyleId = null;
        bind(settings.styleId, settings);
    }

    private static boolean isDarkMode(Settings s) {
        if (s.themeMode == Settings.THEME_LIGHT) return false;
        if (s.themeMode == Settings.THEME_DARK) return true;
        return true; // Auto：深色优先（多数玻璃观感以暗色为设计基准）
    }

    // ==================== 面板构建 ====================

    private void build() {
        mContainer.removeAllViews();
        SettingsViews.addSectionHeader(mContainer, "调整「" + mEffective.name + "」");
        SettingsViews.addNote(mContainer,
                "4 个内置样式是模板：这里的每项调整只对当前选中的样式生效，"
                        + "以补丁形式叠加在模板之上，未改动的参数保持模板原值；"
                        + "可随时「重置调整」回到出厂定义。");

        buildVariantRow();
        buildAccentModeRow();
        buildPaletteCard();
        buildGeometryCard();
        buildElementsCard();
        buildTextCard();
        buildAnimCard();
        buildResetRow();
    }

    /** 渲染策略（variant）：决定渲染器的绘制策略，可整体切换观感。 */
    private void buildVariantRow() {
        List<String> options = Arrays.asList("标准", "磨砂玻璃", "液态玻璃", "霓虹脉冲");
        int index = indexOfVariant(mEffective.variant);
        SettingsViews.addSegmentedItem(mContainer, "渲染策略", options, index,
                i -> putString("variant", variantOf(i)));
    }

    /** 强调色模式：固定自定义色 / 从通知图标取色 / 跟随系统强调色。 */
    private void buildAccentModeRow() {
        List<String> options = Arrays.asList("自定义色", "通知取色", "跟随系统");
        int index = mEffective.accentMode;
        if (index < 0 || index > 2) index = 0;
        SettingsViews.addSegmentedItem(mContainer, "强调色来源", options, index,
                i -> putInt("accentMode", i));
    }

    // ---------- 配色 ----------

    private void buildPaletteCard() {
        SettingsViews.addSectionHeader(mContainer, "配色");
        SettingsViews.addSegmentedItem(mContainer, "编辑目标", Arrays.asList("浅色", "深色"),
                mEditDark ? 1 : 0, i -> {
                    mEditDark = i == 1;
                    build();
                });

        final String pal = mEditDark ? "dark" : "light";
        final StyleSpec.Palette p = mEditDark ? mEffective.dark : mEffective.light;

        addColorRow("背景色（上）", p.bgColors[0], argb -> putPaletteColor(pal, "bg0", argb));
        addColorRow("背景色（下）", p.bgColors.length > 1 ? p.bgColors[1] : p.bgColors[0],
                argb -> putPaletteColor(pal, "bg1", argb));
        addColorRow("强调色", p.accent, argb -> putPaletteColor(pal, "accent", argb));
        addColorRow("主文字", p.textPrimary, argb -> putPaletteColor(pal, "textPrimary", argb));
        addColorRow("次文字", p.textSecondary,
                argb -> putPaletteColor(pal, "textSecondary", argb));
        addColorRow("应用名", p.appNameColor,
                argb -> putPaletteColor(pal, "appNameColor", argb));
        addColorRow("描边", p.strokeColor, argb -> putPaletteColor(pal, "strokeColor", argb));
        addColorRow("顶部高光", p.highlightColor,
                argb -> putPaletteColor(pal, "highlightColor", argb));
        addColorRow("底部阴影", p.shadowColor,
                argb -> putPaletteColor(pal, "shadowColor", argb));
        addColorRow("图标底色", p.iconBgColor,
                argb -> putPaletteColor(pal, "iconBgColor", argb));
        addColorRow("图标描边", p.iconStrokeColor,
                argb -> putPaletteColor(pal, "iconStrokeColor", argb));
        addColorRow("进度环", p.ringColor, argb -> putPaletteColor(pal, "ringColor", argb));
        addColorRow("进度环轨道", p.ringTrackColor,
                argb -> putPaletteColor(pal, "ringTrackColor", argb));
        addColorRow("脉冲点", p.pulseColor, argb -> putPaletteColor(pal, "pulseColor", argb));
        addColorRow("玻璃罩层", mEffective.scrimColor,
                argb -> putInt("scrimColor", argb));

        SettingsViews.addNote(mContainer,
                "取色器支持 Alpha 通道：玻璃罩层、描边、高光等半透明色可直接调透明度。"
                        + "「跟随系统」强调色取设备当前 Material You 动态色。");
    }

    /** 一行颜色项：左侧标签 + 当前色块；点击弹出自绘取色器。 */
    private void addColorRow(String label, int argb, java.util.function.IntConsumer onPicked) {
        LinearLayout row = new LinearLayout(mCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = SettingsViews.dp(mCtx, 14);
        row.setPadding(pad, pad, pad, pad);

        android.widget.TextView tv = new android.widget.TextView(mCtx);
        tv.setText(label);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        android.widget.TextView hex = new android.widget.TextView(mCtx);
        hex.setText(StyleSpec.colorHex(argb));
        hex.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        hex.setTypeface(android.graphics.Typeface.MONOSPACE);
        hex.setAlpha(0.7f);
        row.addView(hex);

        android.view.View swatch = new android.view.View(mCtx);
        int size = SettingsViews.dp(mCtx, 30);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(size, size);
        slp.setMarginStart(SettingsViews.dp(mCtx, 10));
        swatch.setLayoutParams(slp);
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        d.setCornerRadius(SettingsViews.dp(mCtx, 8));
        d.setColor(argb);
        d.setStroke(SettingsViews.dp(mCtx, 1), 0x66000000);
        swatch.setBackground(d);
        row.addView(swatch);

        row.setOnClickListener(v -> ColorPickerDialog.show(mCtx, label, argb, color -> {
            d.setColor(color);
            hex.setText(StyleSpec.colorHex(color));
            onPicked.accept(color);
        }));

        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(mCtx);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, SettingsViews.dp(mCtx, 6));
        card.setLayoutParams(clp);
        card.setCardElevation(0f);
        card.setRadius(SettingsViews.dp(mCtx, 16));
        card.addView(row);
        mContainer.addView(card);
    }

    // ---------- 几何 ----------

    private void buildGeometryCard() {
        SettingsViews.addSectionHeader(mContainer, "几何与尺寸");
        addFloatSlider("圆角半径", 0f, 48f, 1f, mEffective.cornerRadiusDp, "dp",
                v -> putFloat("cornerRadiusDp", v));
        addFloatSlider("描边宽度", 0f, 4f, 0.1f, mEffective.strokeWidthDp, "dp",
                v -> putFloat("strokeWidthDp", v));
        addFloatSlider("背景模糊", 0f, 60f, 1f, mEffective.blurRadiusDp, "dp",
                v -> putFloat("blurRadiusDp", v));
        addFloatSlider("内边距（水平）", 0f, 64f, 1f, mEffective.paddingHDp, "dp",
                v -> putFloat("paddingHDp", v));
        addFloatSlider("内边距（垂直）", 0f, 48f, 1f, mEffective.paddingVDp, "dp",
                v -> putFloat("paddingVDp", v));
        addFloatSlider("图标尺寸", 16f, 96f, 1f, mEffective.iconSizeDp, "dp",
                v -> putFloat("iconSizeDp", v));
        addFloatSlider("图标内边距", 0f, 24f, 1f, mEffective.iconPaddingDp, "dp",
                v -> putFloat("iconPaddingDp", v));
        addFloatSlider("阴影层级", 0f, 32f, 1f, mEffective.elevationDp, "dp",
                v -> putFloat("elevationDp", v));
        addFloatSlider("最小高度", 32f, 200f, 1f, mEffective.minHeightDp, "dp",
                v -> putFloat("minHeightDp", v));
    }

    // ---------- 元素显隐 ----------

    private void buildElementsCard() {
        SettingsViews.addSectionHeader(mContainer, "元素与显隐");
        addBoolSwitch("显示应用图标", mEffective.showIcon, v -> putBool("showIcon", v));
        addBoolSwitch("显示应用名", mEffective.showAppName, v -> putBool("showAppName", v));
        addBoolSwitch("图标圆形底", mEffective.iconCircleBg, v -> putBool("iconCircleBg", v));
        addBoolSwitch("顶部高光", mEffective.topHighlight, v -> putBool("topHighlight", v));
        addBoolSwitch("底部接触阴影", mEffective.bottomShadow, v -> putBool("bottomShadow", v));
        addBoolSwitch("双描边（折射感）", mEffective.doubleStroke, v -> putBool("doubleStroke", v));
        addBoolSwitch("倒计时进度环", mEffective.progressRing, v -> putBool("progressRing", v));
        addBoolSwitch("呼吸脉冲点", mEffective.pulseDot, v -> putBool("pulseDot", v));
        addFloatSlider("正文最大行数", 1f, 5f, 1f, mEffective.maxTextLines, " 行",
                v -> putInt("maxTextLines", Math.round(v)));
    }

    // ---------- 文字 ----------

    private void buildTextCard() {
        SettingsViews.addSectionHeader(mContainer, "文字");
        addFloatSlider("标题字号", 8f, 32f, 0.5f, mEffective.titleSizeSp, "sp",
                v -> putFloat("titleSizeSp", v));
        addFloatSlider("正文字号", 8f, 28f, 0.5f, mEffective.textSizeSp, "sp",
                v -> putFloat("textSizeSp", v));
        addFloatSlider("应用名字号", 6f, 20f, 0.5f, mEffective.appNameSizeSp, "sp",
                v -> putFloat("appNameSizeSp", v));
    }

    // ---------- 动画 ----------

    private void buildAnimCard() {
        SettingsViews.addSectionHeader(mContainer, "动画（样式默认值）");
        List<String> anims = Arrays.asList("跟随样式", "滑动", "缩放", "弹性", "淡入");
        SettingsViews.addSegmentedItem(mContainer, "入场动画", anims, mEffective.enterAnim,
                i -> putInt("enterAnim", i));
        SettingsViews.addSegmentedItem(mContainer, "退场动画", anims, mEffective.exitAnim,
                i -> putInt("exitAnim", i));
        addFloatSlider("入场时长", 60f, 1500f, 20f, mEffective.enterDurationMs, "ms",
                v -> putInt("enterDurationMs", Math.round(v)));
        addFloatSlider("退场时长", 60f, 1500f, 20f, mEffective.exitDurationMs, "ms",
                v -> putInt("exitDurationMs", Math.round(v)));
        addFloatSlider("回弹强度", 0f, 3f, 0.1f, mEffective.enterOvershoot, "",
                v -> putFloat("enterOvershoot", v));
        SettingsViews.addNote(mContainer,
                "这里是样式自带的动画默认值；「动画」页的用户选择优先级更高。");
    }

    private void buildResetRow() {
        SettingsViews.addSectionHeader(mContainer, "重置");
        SettingsViews.addEntryItem(mContainer, "重置全部调整",
                "删除当前样式的全部微调，恢复出厂模板", () -> {
                    mPatch = new JSONObject();
                    save();
                    android.widget.Toast.makeText(mCtx,
                            "已重置「" + mEffective.name + "」的调整",
                            android.widget.Toast.LENGTH_SHORT).show();
                    mEffective = StyleRegistry.byId(mStyleId, mCustomStylesJson, "{}");
                    build();
                });
    }

    // ==================== 控件帮助 ====================

    private void addFloatSlider(String title, float min, float max, float step, float value,
                                String unit, SettingsViews.OnFloatChange l) {
        SettingsViews.addSliderItem(mContainer, title, min, max, step, value, unit, l);
    }

    private void addBoolSwitch(String title, boolean value, SettingsViews.OnBooleanChange l) {
        SettingsViews.addSwitchItem(mContainer, title, null, value, l);
    }

    // ==================== 补丁写入 ====================

    private void putFloat(String key, float v) {
        try {
            mPatch.put(key, v);
        } catch (JSONException ignored) {
        }
        afterEdit();
    }

    private void putInt(String key, int v) {
        try {
            mPatch.put(key, v);
        } catch (JSONException ignored) {
        }
        afterEdit();
    }

    private void putBool(String key, boolean v) {
        try {
            mPatch.put(key, v);
        } catch (JSONException ignored) {
        }
        afterEdit();
    }

    private void putString(String key, String v) {
        try {
            mPatch.put(key, v);
        } catch (JSONException ignored) {
        }
        afterEdit();
    }

    /** 调色板字段：bg0/bg1 合并写回 bgColors 数组（其余直接同名键）。 */
    private void putPaletteColor(String pal, String key, int argb) {
        try {
            JSONObject p = mPatch.optJSONObject(pal);
            if (p == null) {
                p = new JSONObject();
                mPatch.put(pal, p);
            }
            if ("bg0".equals(key) || "bg1".equals(key)) {
                JSONArrayHolder.writeBgColor(p, key, argb, pal, mEffective);
            } else {
                p.put(key, StyleSpec.colorHex(argb));
            }
        } catch (JSONException ignored) {
        }
        afterEdit();
    }

    /** 写盘 + 刷新生效样式 + 驱动预览重渲染（节流）。 */
    private void afterEdit() {
        save();
        // 立即在内存中合并出新生效值，保证同一面板内连续改动的初值链是最新的
        mEffective = StyleRegistry.byId(mStyleId, mCustomStylesJson, patchRootJson());
        mUiHandler.removeCallbacks(mCoalescedRefresh);
        mUiHandler.postDelayed(mCoalescedRefresh, 60);
    }

    private void save() {
        try {
            JSONObject root = new JSONObject();
            String existing = mStore.prefs().getString("hulfix_style_overrides", "{}");
            if (existing != null && !existing.isEmpty()) root = new JSONObject(existing);
            if (mPatch.length() == 0) root.remove(mStyleId);
            else root.put(mStyleId, mPatch);
            mStore.putString("hulfix_style_overrides", root.toString());
        } catch (JSONException ignored) {
        }
    }

    private String patchRootJson() {
        try {
            JSONObject root = new JSONObject();
            root.put(mStyleId, mPatch);
            return root.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    // ==================== variant 映射 ====================

    private static int indexOfVariant(String v) {
        if (StyleSpec.VARIANT_STANDARD.equals(v)) return 0;
        if (StyleSpec.VARIANT_GLASS.equals(v)) return 1;
        if (StyleSpec.VARIANT_LIQUID.equals(v)) return 2;
        if (StyleSpec.VARIANT_NEON.equals(v)) return 3;
        return 1;
    }

    private static String variantOf(int i) {
        switch (i) {
            case 0:
                return StyleSpec.VARIANT_STANDARD;
            case 2:
                return StyleSpec.VARIANT_LIQUID;
            case 3:
                return StyleSpec.VARIANT_NEON;
            default:
                return StyleSpec.VARIANT_GLASS;
        }
    }

    /** bg0/bg1 与 bgColors 数组的合并工具（保持数组两元素语义）。 */
    private static final class JSONArrayHolder {
        private JSONArrayHolder() {
        }

        static void writeBgColor(JSONObject p, String key, int argb, String pal,
                                 StyleSpec effective) throws JSONException {
            org.json.JSONArray arr = p.optJSONArray("bgColors");
            if (arr == null || arr.length() < 2) {
                // 以当前生效数组为基底新建，避免丢失另一个停止色
                int[] cur = "dark".equals(pal) ? effective.dark.bgColors : effective.light.bgColors;
                arr = new org.json.JSONArray();
                arr.put(StyleSpec.colorHex(cur[0]));
                arr.put(StyleSpec.colorHex(cur.length > 1 ? cur[1] : cur[0]));
            }
            int idx = "bg0".equals(key) ? 0 : 1;
            arr.put(idx, StyleSpec.colorHex(argb));
            p.put("bgColors", arr);
        }
    }
}
