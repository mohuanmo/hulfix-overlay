package com.example.hulfix.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.example.hulfix.R;
import com.example.hulfix.core.Settings;
import com.example.hulfix.overlay.LensEngine;
import com.example.hulfix.overlay.StyleRegistry;
import com.example.hulfix.overlay.StyleSpec;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 样式页：内置 4 种样式（标准安卓 / M3 磨砂玻璃 / iOS 液态玻璃 / 霓虹脉冲）的
 * 选择、实时预览、播放动画，以及自定义样式的文件导入 / JSON 粘贴导入。
 */
public class StyleFragment extends BaseSettingsFragment {

    private StylePreviewView mBigPreview;
    private LinearLayout mStyleList;
    private ActivityResultLauncher<Intent> mFilePicker;

    // 列表重建守卫：仅当"选中样式 id"或"自定义样式数量"变化时重建卡片列表，
    // 否则滑块拖动等连续刷新只更新大预览，避免反复重建 6 张预览卡造成卡顿。
    private String mRenderedStyleId = " ";
    private int mRenderedCustomCount = -1;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mFilePicker = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != android.app.Activity.RESULT_OK
                            || result.getData() == null
                            || result.getData().getData() == null) {
                        return;
                    }
                    importFromUri(result.getData().getData());
                });
    }

    @Override
    protected void buildUI(LinearLayout container) {
        // ---- 顶部大预览 ----
        SettingsViews.addSectionHeader(container, "当前样式");

        com.google.android.material.card.MaterialCardView previewCard =
                new com.google.android.material.card.MaterialCardView(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, SettingsViews.dp(requireContext(), 8));
        previewCard.setLayoutParams(lp);
        previewCard.setCardElevation(SettingsViews.dp(requireContext(), 1));
        previewCard.setRadius(SettingsViews.dp(requireContext(), 20));

        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsViews.dp(requireContext(), 12);
        box.setPadding(pad, pad, pad, pad);

        mBigPreview = new StylePreviewView(requireContext());
        mBigPreview.setMinimumHeight(SettingsViews.dp(requireContext(), 150));
        box.addView(mBigPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        com.google.android.material.button.MaterialButton playBtn =
                new com.google.android.material.button.MaterialButton(requireContext());
        playBtn.setText("播放动画");
        playBtn.setIcon(androidx.core.content.ContextCompat.getDrawable(
                requireContext(), com.example.hulfix.R.drawable.ic_play));
        playBtn.setOnClickListener(v -> mBigPreview.replay());
        box.addView(playBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        previewCard.addView(box);
        container.addView(previewCard);

        // ---- 明暗模式 ----
        SettingsViews.addSectionHeader(container, "明暗适配");
        SettingsViews.addSegmentedItem(container, "配色模式",
                java.util.Arrays.asList("跟随系统", "浅色", "深色"),
                prefInt("hulfix_theme_mode", Settings.THEME_AUTO),
                index -> store.putInt("hulfix_theme_mode", index));

        // ---- 主题颜色（仅影响本 App 界面）----
        SettingsViews.addSectionHeader(container, "主题颜色");
        container.addView(buildThemeColorCard());
        SettingsViews.addNote(container,
                "主题颜色只影响本 App 的设置界面观感，不影响悬浮窗样式。");

        // ---- AGSL 真折射引擎 ----
        SettingsViews.addSectionHeader(container, "真折射引擎（AGSL）");
        if (!LensEngine.isSupported()) {
            SettingsViews.addNote(container,
                    "当前设备不支持 AGSL 真折射（需要 Android 13 / API 33 及以上）。"
                            + "液态玻璃样式已自动使用兼容模拟效果（窗口模糊 + 双描边 + 高光），观感接近且零风险。");
        } else {
            SettingsViews.addSwitchItem(container, "启用真折射引擎",
                    "液态玻璃启用物理折射：Snell 定律光线偏折 + RGB 色散 + 菲涅尔高光",
                    prefBoolean("hulfix_lens_enabled", true),
                    v -> {
                        store.putBoolean("hulfix_lens_enabled", v);
                        refreshPreview();
                    });
            SettingsViews.addSliderItem(container, "折射强度",
                    0f, 48f, 1f, prefFloat("hulfix_lens_thickness", 14f), "px",
                    v -> {
                        store.putFloat("hulfix_lens_thickness", v);
                        refreshPreview();
                    });
            SettingsViews.addSliderItem(container, "边缘透镜",
                    0f, 1f, 0.05f, prefFloat("hulfix_lens_edge", 0.75f), "%",
                    v -> {
                        store.putFloat("hulfix_lens_edge", v);
                        refreshPreview();
                    });
            SettingsViews.addSliderItem(container, "色散",
                    0f, 1f, 0.05f, prefFloat("hulfix_lens_dispersion", 0.35f), "%",
                    v -> {
                        store.putFloat("hulfix_lens_dispersion", v);
                        refreshPreview();
                    });
            SettingsViews.addSliderItem(container, "镜面高光",
                    0f, 2f, 0.1f, prefFloat("hulfix_lens_specular", 1f), "",
                    v -> {
                        store.putFloat("hulfix_lens_specular", v);
                        refreshPreview();
                    });
            SettingsViews.addSliderItem(container, "折射率 IOR",
                    1.01f, 2f, 0.01f, prefFloat("hulfix_lens_ior", 1.5f), "",
                    v -> {
                        store.putFloat("hulfix_lens_ior", v);
                        refreshPreview();
                    });
            SettingsViews.addNote(container,
                    "仅对液态玻璃（liquid）类样式生效；修改即时反映在预览中，真实悬浮窗在下次通知时生效。"
                            + "折射强度越大边缘拉伸越明显，色散控制边缘彩虹强度。");
        }

        // ---- 内置样式 ----
        SettingsViews.addSectionHeader(container, "内置样式");
        mStyleList = new LinearLayout(requireContext());
        mStyleList.setOrientation(LinearLayout.VERTICAL);
        container.addView(mStyleList);

        // ---- 导入 ----
        SettingsViews.addSectionHeader(container, "自定义样式");
        SettingsViews.addEntryItem(container, "从文件导入",
                "选择 .json / .txt 样式文件", this::pickFile);
        SettingsViews.addEntryItem(container, "粘贴 JSON 导入",
                "手动粘贴样式定义 JSON", this::pasteJson);
        SettingsViews.addEntryItem(container, "查看样式规范",
                "全部字段、取值范围、默认值与示例 JSON", this::showSpecDoc);
        SettingsViews.addNote(container,
                "样式 JSON 可定义配色、圆角、模糊、描边、字号、动画等参数；导入后立即可选。"
                        + "支持单个对象 / 数组 / {\"styles\":[...]} 三种文件形态。");
    }

    @Override
    protected void refresh() {
        super.refresh();
        if (mBigPreview == null || mStyleList == null) return;
        Settings settings = currentSettings();
        refreshPreview(settings);
        // 仅在选中样式或自定义样式数量变化时重建列表：避免滑块拖动时反复重建 6 张预览卡。
        // 选中变化（点选样式）必然触发此处重建，✓ 勾选状态得以即时刷新。
        int customCount = store.customStyles().size();
        if (!settings.styleId.equals(mRenderedStyleId)
                || customCount != mRenderedCustomCount) {
            renderStyleList(settings);
            mRenderedStyleId = settings.styleId;
            mRenderedCustomCount = customCount;
        }
    }

    /** 用最新设置重建大预览（滑块回调直接调用）。 */
    private void refreshPreview() {
        refreshPreview(currentSettings());
    }

    /** 用最新设置重建大预览（滑块拖动时即时刷新，所见即所得）。 */
    private void refreshPreview(Settings settings) {
        if (mBigPreview == null) return;
        StyleSpec current = StyleRegistry.byId(settings.styleId, settings.customStylesJson);
        mBigPreview.setSettings(settings);
        mBigPreview.setStyle(current, isDarkNow());
    }

    private void renderStyleList(Settings settings) {
        mStyleList.removeAllViews();
        List<StyleSpec> all = StyleRegistry.builtins();
        all.addAll(store.customStyles());

        for (StyleSpec spec : all) {
            boolean selected = spec.id.equals(settings.styleId);
            mStyleList.addView(createStyleCard(spec, selected, settings));
        }
    }

    private View createStyleCard(StyleSpec spec, boolean selected, Settings settings) {
        android.content.Context ctx = requireContext();
        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, SettingsViews.dp(ctx, 8));
        card.setLayoutParams(lp);
        card.setCardElevation(selected ? SettingsViews.dp(ctx, 3) : SettingsViews.dp(ctx, 1));
        card.setRadius(SettingsViews.dp(ctx, 20));
        if (selected) {
            card.setStrokeColor(com.google.android.material.color.MaterialColors.getColor(
                    ctx, com.google.android.material.R.attr.colorPrimary, android.graphics.Color.MAGENTA));
            card.setStrokeWidth(SettingsViews.dp(ctx, 2));
        }

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = SettingsViews.dp(ctx, 12);
        row.setPadding(pad, pad, pad, pad);

        // 左侧小预览（轻量模式：跳过 AGSL 着色器编译，见 StylePreviewView 类注释）
        StylePreviewView mini = StylePreviewView.createLightweight(ctx);
        mini.setSettings(settings);
        mini.setStyle(spec, isDarkNow());
        LinearLayout.LayoutParams miniLp = new LinearLayout.LayoutParams(
                SettingsViews.dp(ctx, 190), SettingsViews.dp(ctx, 96));
        miniLp.setMarginEnd(SettingsViews.dp(ctx, 12));
        row.addView(mini, miniLp);

        // 右侧文字
        LinearLayout textBox = new LinearLayout(ctx);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(ctx);
        name.setText(spec.name + (selected ? "  ✓" : ""));
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textBox.addView(name);

        TextView desc = new TextView(ctx);
        desc.setText(styleDescription(spec));
        desc.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        desc.setAlpha(0.75f);
        desc.setPadding(0, SettingsViews.dp(ctx, 2), 0, 0);
        desc.setMaxLines(2);
        desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
        textBox.addView(desc);

        row.addView(textBox);
        card.addView(row);

        card.setOnClickListener(v -> {
            store.putString("hulfix_style_id", spec.id);
            // 点击时播放一次预览
            mini.replay();
        });
        if (spec.id != null && spec.id.startsWith("custom_")) {
            card.setOnLongClickListener(v -> {
                new MaterialAlertDialogBuilder(ctx)
                        .setTitle("删除样式")
                        .setMessage("确定删除「" + spec.name + "」？")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("删除", (d, w) -> {
                            store.removeCustomStyle(spec.id);
                            String cur = prefString("hulfix_style_id",
                                    StyleRegistry.StyleIds.M3E_GLASS);
                            if (spec.id.equals(cur)) {
                                store.putString("hulfix_style_id",
                                        StyleRegistry.StyleIds.M3E_GLASS);
                            }
                        })
                        .show();
                return true;
            });
        }
        return card;
    }

    // ==================== 主题颜色 ====================

    /**
     * 主题色板：0 = 跟随壁纸动态取色（Material You，Android 12+ 生效）；
     * 1..8 = 预设种子色，索引与 {@link SettingsActivity} 的叠加层数组一一对应。
     */
    private static final int[] THEME_SWATCH_COLORS = {
            0, // 动态取色：多色渐变圆点
            R.color.hulfix_seed_1, R.color.hulfix_seed_2, R.color.hulfix_seed_3,
            R.color.hulfix_seed_4, R.color.hulfix_seed_5, R.color.hulfix_seed_6,
            R.color.hulfix_seed_7, R.color.hulfix_seed_8,
    };
    private static final String[] THEME_SWATCH_NAMES = {
            "跟随壁纸", "绛紫", "海蓝", "湖青", "松绿", "落日橙", "绯红", "蔷薇粉", "石墨灰"
    };

    /** 主题色板卡片：横向滚动的圆形色板 + 当前主题标签。 */
    private View buildThemeColorCard() {
        android.content.Context ctx = requireContext();
        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(ctx);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, 0, 0, SettingsViews.dp(ctx, 8));
        card.setLayoutParams(clp);
        card.setCardElevation(SettingsViews.dp(ctx, 1));
        card.setRadius(SettingsViews.dp(ctx, 20));

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsViews.dp(ctx, 16);
        box.setPadding(pad, SettingsViews.dp(ctx, 12), pad, SettingsViews.dp(ctx, 14));

        int current = prefInt(SettingsActivity.PREF_THEME_SEED, 0);
        if (current < 0 || current >= THEME_SWATCH_NAMES.length) current = 0;
        TextView label = new TextView(ctx);
        label.setText("当前主题：" + THEME_SWATCH_NAMES[current]);
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        box.addView(label);

        HorizontalScrollView hsv = new HorizontalScrollView(ctx);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, SettingsViews.dp(ctx, 10), 0, 0);
        for (int i = 0; i < THEME_SWATCH_COLORS.length; i++) {
            View dot = new View(ctx);
            int size = SettingsViews.dp(ctx, 36);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(size, size);
            dlp.setMarginEnd(SettingsViews.dp(ctx, 10));
            dot.setLayoutParams(dlp);
            dot.setBackground(swatchDrawable(ctx, i, i == current));
            dot.setContentDescription(THEME_SWATCH_NAMES[i]);
            final int index = i;
            dot.setOnClickListener(v -> {
                store.putInt(SettingsActivity.PREF_THEME_SEED, index);
                // 重建 Activity：让新主题贯穿整个设置界面（各页状态由 prefs 持久化保留）
                requireActivity().recreate();
            });
            row.addView(dot);
        }
        hsv.addView(row);
        box.addView(hsv);
        card.addView(box);
        return card;
    }

    /** 单个色板圆点：种子色实心圆；0 号位为四色渐变（寓意动态取色）；选中加描边环。 */
    private static android.graphics.drawable.GradientDrawable swatchDrawable(
            android.content.Context ctx, int index, boolean selected) {
        android.graphics.drawable.GradientDrawable d =
                new android.graphics.drawable.GradientDrawable();
        d.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (index == 0) {
            d.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
            d.setColors(new int[]{0xFF4285F4, 0xFFEA4335, 0xFFFBBC04, 0xFF34A853});
        } else {
            d.setColor(androidx.core.content.ContextCompat.getColor(
                    ctx, THEME_SWATCH_COLORS[index]));
        }
        if (selected) {
            int ring = com.google.android.material.color.MaterialColors.getColor(ctx,
                    com.google.android.material.R.attr.colorOnSurface, 0xFF000000);
            d.setStroke(SettingsViews.dp(ctx, 3), ring);
        }
        return d;
    }

    private String styleDescription(StyleSpec spec) {
        switch (spec.id) {
            case "standard":
                return "AOSP 原生 Heads-Up 观感，纯净直接，资源占用最低";
            case "m3e_glass":
                return "Material 3 Expressive：28dp 大圆角 + 磨砂玻璃 + 弹性动效";
            case "ios_liquid":
                return "仿 iOS 26 液态玻璃：双层折射描边 + 强模糊 + 凝华入场";
            case "neon_pulse":
                return "自研霓虹脉冲：暗色玻璃 + 渐变描边 + 倒计时进度环";
            default:
                return "自定义样式（长按卡片可删除）";
        }
    }

    // ==================== 导入 ====================

    private void pickFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        mFilePicker.launch(intent);
    }

    private void importFromUri(android.net.Uri uri) {
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            importJson(sb.toString());
        } catch (Throwable t) {
            toast("读取文件失败：" + t.getMessage());
        }
    }

    private void pasteJson() {
        EditText input = new EditText(requireContext());
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinHeight(SettingsViews.dp(requireContext(), 200));
        input.setHint("{\"name\":\"我的样式\", \"variant\":\"glass\", ...}");
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("粘贴样式 JSON")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (d, w) ->
                        importJson(input.getText().toString()))
                .show();
    }

    /** 展示内置的完整样式规范（字段 / 取值范围 / 默认值 / 示例）。 */
    private void showSpecDoc() {
        android.content.Context ctx = requireContext();
        TextView tv = new TextView(ctx);
        tv.setText(StyleImportDoc.SPEC_TEXT);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        int pad = SettingsViews.dp(ctx, 16);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(tv);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsViews.dp(ctx, 380)));
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("自定义样式规范")
                .setView(scroll)
                .setNeutralButton("复制示例", (d, w) -> copyExample())
                .setPositiveButton("知道了", null)
                .show();
    }

    private void copyExample() {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "hulfix-style", StyleImportDoc.EXAMPLE_JSON));
            toast("示例 JSON 已复制，可用「粘贴 JSON 导入」直接导入");
        } catch (Throwable t) {
            toast("复制失败：" + t.getMessage());
        }
    }

    private void importJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            toast("内容为空");
            return;
        }
        try {
            List<StyleSpec> specs = StyleSpec.listFromJson(json);
            if (specs.isEmpty()) {
                toast("未解析到样式");
                return;
            }
            long base = System.currentTimeMillis();
            for (int i = 0; i < specs.size(); i++) {
                StyleSpec spec = specs.get(i);
                if (!spec.builtin) {
                    // 加序号后缀：同一毫秒批量导入多个样式时避免 id 撞车互相覆盖
                    spec.id = "custom_" + (base + i);
                }
                StyleSpec.clamp(spec);
                store.addCustomStyle(spec);
            }
            toast("已导入 " + specs.size() + " 个样式");
        } catch (Throwable t) {
            toast("JSON 解析失败：" + t.getMessage());
        }
    }

    private void toast(String msg) {
        if (getView() != null) {
            Snackbar.make(getView(), msg, Snackbar.LENGTH_LONG).show();
        }
    }
}
