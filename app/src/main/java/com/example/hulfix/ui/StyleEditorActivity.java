package com.example.hulfix.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;

import com.example.hulfix.R;
import com.example.hulfix.core.Settings;
import com.example.hulfix.core.SettingsRepository;
import com.example.hulfix.overlay.StyleRegistry;
import com.example.hulfix.overlay.StyleSpec;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

/**
 * 可视化编辑器（实时渲染）：全屏编辑当前选中样式的每一项参数。
 *
 * <p>与样式页的「调整当前样式」面板是同一套控件（{@link StyleAdjustPanel}），
 * 差异在场景：这里是<b>专职编辑台</b>——顶部大预览 + 播放动画让每次拖动都实时
 * 重渲染（所见即所得），下方聚齐全量参数（样式 + 引擎），底部提供调整补丁的
 * 导出 / 导入，形成「编辑 → 导出 → 分享 → 导入」的闭环。</p>
 *
 * <p>顶部样式切换条决定"编辑哪个样式"（写入 {@code hulfix_style_id}，
 * 与悬浮窗实际使用的选择保持一致）。</p>
 */
public class StyleEditorActivity extends AppCompatActivity {

    private StylePreviewView mPreview;
    private LinearLayout mAdjustContainer;
    private StyleAdjustPanel mPanel;
    private LinearLayout mChipRow;
    private SettingsStore mStore;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsStore.init(this);
        mStore = SettingsStore.get(this);
        setContentView(buildRoot());
        refreshAll();
    }

    @NonNull
    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setFitsSystemWindows(true);

        // ---------- 顶部返回栏 ----------
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = SettingsViews.dp(this, 12);
        header.setPadding(pad, pad, pad, pad);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 26);
        back.setPadding(SettingsViews.dp(this, 4), 0, SettingsViews.dp(this, 12), 0);
        back.setOnClickListener(v -> finish());
        header.addView(back);

        TextView title = new TextView(this);
        title.setText("可视化编辑器");
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        // ---------- 滚动内容 ----------
        LinearLayout content = SettingsViews.createContainer(this);

        // 大预览（实时渲染）
        com.google.android.material.card.MaterialCardView previewCard =
                new com.google.android.material.card.MaterialCardView(this);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, 0, 0, SettingsViews.dp(this, 8));
        previewCard.setLayoutParams(plp);
        previewCard.setCardElevation(SettingsViews.dp(this, 1));
        previewCard.setRadius(SettingsViews.dp(this, 20));
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int bpad = SettingsViews.dp(this, 12);
        box.setPadding(bpad, bpad, bpad, bpad);
        mPreview = new StylePreviewView(this);
        mPreview.setMinimumHeight(SettingsViews.dp(this, 170));
        box.addView(mPreview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        com.google.android.material.button.MaterialButton playBtn =
                new com.google.android.material.button.MaterialButton(this);
        playBtn.setText("播放动画");
        playBtn.setIcon(androidx.core.content.ContextCompat.getDrawable(
                this, R.drawable.ic_play));
        playBtn.setOnClickListener(v -> mPreview.replay());
        box.addView(playBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        previewCard.addView(box);
        content.addView(previewCard);

        // 样式切换条
        SettingsViews.addSectionHeader(content, "编辑哪个样式");
        mChipRow = new LinearLayout(this);
        mChipRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        hsv.addView(mChipRow);
        content.addView(hsv);

        // 逐样式调整面板（与样式页同一套控件）
        mAdjustContainer = new LinearLayout(this);
        mAdjustContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(mAdjustContainer);
        mPanel = new StyleAdjustPanel(this, mStore, mAdjustContainer, this::refreshPreview);

        // 引擎参数（全局）
        LensEngineSection.add(content, mStore, this::refreshPreview);

        // 导入 / 导出
        SettingsViews.addSectionHeader(content, "调整补丁 导入 / 导出");
        SettingsViews.addEntryItem(content, "导出为样式文件",
                "把当前样式（含全部调整）导出为 JSON，可在样式页粘贴导入为新样式",
                this::exportStyleFile);
        SettingsViews.addEntryItem(content, "导出调整补丁",
                "只导出相对模板的差异（增量补丁），可分享给同款模板复用",
                this::exportPatch);
        SettingsViews.addEntryItem(content, "导入调整补丁",
                "粘贴增量补丁 JSON，合并到当前样式的调整上",
                this::importPatch);
        SettingsViews.addNote(content,
                "补丁是局部差异：只覆盖 JSON 中出现的参数，其余保持模板/导入定义。");

        NestedScrollView scroll = new NestedScrollView(this);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.addView(SettingsViews.wrapCentered(this, content));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    // ==================== 刷新 ====================

    private void refreshAll() {
        Settings settings = SettingsRepository.readSettings(mStore.prefs());
        renderChips(settings);
        mPanel.rebind(settings);
        refreshPreview(settings);
    }

    private void refreshPreview() {
        refreshPreview(SettingsRepository.readSettings(mStore.prefs()));
    }

    private void refreshPreview(Settings settings) {
        if (mPreview == null) return;
        StyleSpec spec = StyleRegistry.byId(settings.styleId, settings.customStylesJson,
                settings.styleOverridesJson);
        mPreview.setSettings(settings);
        mPreview.setStyle(spec, isDarkNow());
    }

    private boolean isDarkNow() {
        int mode = mStore.prefs().getInt("hulfix_theme_mode", Settings.THEME_AUTO);
        if (mode == Settings.THEME_LIGHT) return false;
        if (mode == Settings.THEME_DARK) return true;
        int uiMode = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    // ==================== 样式切换条 ====================

    private void renderChips(Settings settings) {
        mChipRow.removeAllViews();
        java.util.List<StyleSpec> all = StyleRegistry.builtins();
        all.addAll(mStore.customStyles());
        for (StyleSpec spec : all) {
            boolean selected = spec.id.equals(settings.styleId);
            TextView chip = new TextView(this);
            chip.setText(spec.name);
            chip.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setPadding(SettingsViews.dp(this, 14), SettingsViews.dp(this, 8),
                    SettingsViews.dp(this, 14), SettingsViews.dp(this, 8));
            android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(SettingsViews.dp(this, 18));
            bg.setColor(selected
                    ? com.google.android.material.color.MaterialColors.getColor(this,
                    com.google.android.material.R.attr.colorPrimary, 0xFF6750A4)
                    : 0x00000000);
            bg.setStroke(SettingsViews.dp(this, 1), selected ? 0x00000000 : 0x66000000);
            chip.setBackground(bg);
            chip.setTextColor(selected ? 0xFFFFFFFF : 0xDE000000);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(SettingsViews.dp(this, 8));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> {
                mStore.putString("hulfix_style_id", spec.id);
                refreshAll();
            });
            mChipRow.addView(chip);
        }
    }

    // ==================== 导入 / 导出 ====================

    private void exportStyleFile() {
        Settings settings = SettingsRepository.readSettings(mStore.prefs());
        StyleSpec spec = StyleRegistry.byId(settings.styleId, settings.customStylesJson,
                settings.styleOverridesJson);
        try {
            copyToClipboard("hulfix-style", spec.toJson().toString(2));
            toast("样式 JSON 已复制：可在样式页「粘贴 JSON 导入」导入为新样式");
        } catch (Throwable t) {
            toast("导出失败：" + t.getMessage());
        }
    }

    private void exportPatch() {
        String styleId = mStore.prefs().getString("hulfix_style_id",
                StyleRegistry.StyleIds.M3E_GLASS);
        JSONObject patch = StyleRegistry.overridePatch(
                mStore.prefs().getString("hulfix_style_overrides", "{}"), styleId);
        if (patch == null || patch.length() == 0) {
            toast("当前样式没有调整（与模板一致）");
            return;
        }
        try {
            copyToClipboard("hulfix-patch", patch.toString(2));
            toast("调整补丁已复制");
        } catch (Throwable t) {
            toast("导出失败：" + t.getMessage());
        }
    }

    private void importPatch() {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint("{\"cornerRadiusDp\": 12, \"dark\": {\"accent\": \"#FF00E5FF\"}}");
        input.setMinHeight(SettingsViews.dp(this, 200));
        new MaterialAlertDialogBuilder(this)
                .setTitle("导入调整补丁")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("合并", (d, w) -> mergePatch(input.getText().toString()))
                .show();
    }

    /** 把导入的补丁合并进当前样式的调整（键级合并：嵌套调色板逐键覆盖）。 */
    private void mergePatch(String json) {
        if (json == null || json.trim().isEmpty()) {
            toast("内容为空");
            return;
        }
        try {
            JSONObject incoming = new JSONObject(json);
            String styleId = mStore.prefs().getString("hulfix_style_id",
                    StyleRegistry.StyleIds.M3E_GLASS);
            JSONObject root = new JSONObject(
                    mStore.prefs().getString("hulfix_style_overrides", "{}"));
            JSONObject patch = root.optJSONObject(styleId);
            if (patch == null) patch = new JSONObject();
            mergeInto(patch, incoming);
            root.put(styleId, patch);
            mStore.putString("hulfix_style_overrides", root.toString());
            refreshAll();
            toast("已合并调整补丁");
        } catch (Throwable t) {
            toast("JSON 解析失败：" + t.getMessage());
        }
    }

    /** 递归键级合并：src 中出现的键覆盖 dst；嵌套对象（调色板）逐键合并而非整体替换。 */
    private static void mergeInto(JSONObject dst, JSONObject src) throws org.json.JSONException {
        java.util.Iterator<String> it = src.keys();
        while (it.hasNext()) {
            String key = it.next();
            Object sv = src.get(key);
            Object dv = dst.opt(key);
            if (sv instanceof JSONObject && dv instanceof JSONObject) {
                mergeInto((JSONObject) dv, (JSONObject) sv);
            } else {
                dst.put(key, sv);
            }
        }
    }

    private void copyToClipboard(String label, String text) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text));
    }

    private void toast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    }
}
