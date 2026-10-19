package com.example.hulfix.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.example.hulfix.core.PluginManager;
import com.example.hulfix.core.Settings;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 插件页：安装 / 启用 / 停用 / 删除插件，应用插件携带的引擎预设。
 *
 * <p>插件可携带样式（进入样式列表，立即可选）与引擎预设（一键应用一组
 * AGSL 真折射 / 动态光效参数）。导入支持本地文件与粘贴 JSON 两种方式；
 * 完整规范见 {@link PluginImportDoc}。</p>
 */
public class PluginsFragment extends BaseSettingsFragment {

    private LinearLayout mList;
    private LinearLayout mPresetBox;
    private ActivityResultLauncher<Intent> mFilePicker;

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
        SettingsViews.addSectionHeader(container, "已安装插件");
        mList = new LinearLayout(requireContext());
        mList.setOrientation(LinearLayout.VERTICAL);
        container.addView(mList);

        SettingsViews.addSectionHeader(container, "引擎预设");
        SettingsViews.addNote(container,
                "插件可携带引擎预设（一组真折射 / 动态光效参数），点击即整套应用。");
        mPresetBox = new LinearLayout(requireContext());
        mPresetBox.setOrientation(LinearLayout.VERTICAL);
        container.addView(mPresetBox);

        SettingsViews.addSectionHeader(container, "导入插件");
        SettingsViews.addEntryItem(container, "从文件导入",
                "选择插件 .json / .txt 文件", this::pickFile);
        SettingsViews.addEntryItem(container, "粘贴 JSON 导入",
                "手动粘贴插件定义 JSON", this::pasteJson);
        SettingsViews.addEntryItem(container, "查看插件规范",
                "插件格式、导入规则、启停语义与示例", this::showSpecDoc);
        SettingsViews.addEntryItem(container, "复制示例插件",
                "复制示例 JSON，可直接粘贴导入体验", this::copyExample);
        SettingsViews.addNote(container,
                "插件样式导入后出现在样式页列表，与普通自定义样式一样可选；"
                        + "停用插件只会把它的样式移出列表，记录与预设保留。");
    }

    @Override
    protected void refresh() {
        super.refresh();
        if (mList == null || mPresetBox == null) return;
        renderList();
        renderPresets();
    }

    // ==================== 列表 ====================

    private void renderList() {
        mList.removeAllViews();
        List<PluginManager.Plugin> plugins = store.plugins();
        if (plugins.isEmpty()) {
            SettingsViews.addNote(mList, "还没有安装任何插件。");
            return;
        }
        for (PluginManager.Plugin p : plugins) {
            mList.addView(buildPluginCard(p));
        }
    }

    private View buildPluginCard(PluginManager.Plugin p) {
        android.content.Context ctx = requireContext();
        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, SettingsViews.dp(ctx, 8));
        card.setLayoutParams(lp);
        card.setCardElevation(SettingsViews.dp(ctx, 1));
        card.setRadius(SettingsViews.dp(ctx, 20));

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsViews.dp(ctx, 14);
        box.setPadding(pad, pad, pad, pad);

        // 标题行：名称 + 启用开关
        LinearLayout titleRow = new LinearLayout(ctx);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView name = new TextView(ctx);
        name.setText(PluginManager.displayName(p) + "  v" + p.version);
        name.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        name.setTypeface(name.getTypeface(), android.graphics.Typeface.BOLD);
        name.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(name);

        com.google.android.material.materialswitch.MaterialSwitch sw =
                new com.google.android.material.materialswitch.MaterialSwitch(ctx);
        sw.setChecked(p.enabled);
        sw.setClickable(false);
        sw.setFocusable(false);
        titleRow.addView(sw);
        card.setOnClickListener(v -> {
            sw.setChecked(!sw.isChecked());
            store.setPluginEnabled(p.id, sw.isChecked());
            toast((sw.isChecked() ? "已启用「" : "已停用「")
                    + PluginManager.displayName(p) + "」");
        });
        box.addView(titleRow);

        TextView desc = new TextView(ctx);
        String text = (p.author == null || p.author.isEmpty() ? "" : "作者：" + p.author + "\n")
                + (p.description == null || p.description.isEmpty() ? "" : p.description + "\n")
                + "样式 " + p.styles.size() + " 个"
                + (p.engine != null ? " · 引擎预设 1 个" : "");
        desc.setText(text);
        desc.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        desc.setAlpha(0.75f);
        desc.setPadding(0, SettingsViews.dp(ctx, 4), 0, 0);
        box.addView(desc);

        // 操作行
        LinearLayout ops = new LinearLayout(ctx);
        ops.setOrientation(LinearLayout.HORIZONTAL);
        ops.setPadding(0, SettingsViews.dp(ctx, 8), 0, 0);
        if (!p.styles.isEmpty()) {
            com.google.android.material.button.MaterialButton useBtn =
                    new com.google.android.material.button.MaterialButton(ctx, null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle);
            useBtn.setText("选用其样式");
            useBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            useBtn.setOnClickListener(v -> {
                store.putString("hulfix_style_id", p.styles.get(0).id);
                toast("已切换到「" + p.styles.get(0).name + "」");
            });
            ops.addView(useBtn);
        }
        com.google.android.material.button.MaterialButton delBtn =
                new com.google.android.material.button.MaterialButton(ctx, null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle);
        delBtn.setText("删除");
        delBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        delBtn.setOnClickListener(v -> new MaterialAlertDialogBuilder(ctx)
                .setTitle("删除插件")
                .setMessage("确定删除「" + PluginManager.displayName(p) + "」？"
                        + "其样式也会从样式列表移除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    store.removePlugin(p.id);
                    toast("已删除「" + PluginManager.displayName(p) + "」");
                })
                .show());
        ops.addView(delBtn);
        box.addView(ops);

        card.addView(box);
        return card;
    }

    // ==================== 引擎预设 ====================

    private void renderPresets() {
        mPresetBox.removeAllViews();
        List<PluginManager.EnginePreset> presets =
                PluginManager.enginePresetsOf(store.plugins());
        if (presets.isEmpty()) {
            SettingsViews.addNote(mPresetBox,
                    "当前没有可用的引擎预设（安装带 engine 字段的插件后出现）。");
            return;
        }
        for (PluginManager.EnginePreset preset : presets) {
            SettingsViews.addEntryItem(mPresetBox,
                    preset.name == null || preset.name.isEmpty() ? "引擎预设" : preset.name,
                    "点击应用这组真折射 / 动态光效参数",
                    () -> new MaterialAlertDialogBuilder(requireContext())
                            .setTitle("应用引擎预设")
                            .setMessage("用「" + preset.name + "」覆盖当前引擎参数？")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("应用", (d, w) -> {
                                store.applyEnginePreset(preset);
                                toast("已应用引擎预设：" + preset.name);
                            })
                            .show());
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
        input.setHint("{\"name\":\"我的插件\", \"styles\":[...], \"engine\":{...}}");
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("粘贴插件 JSON")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (d, w) ->
                        importJson(input.getText().toString()))
                .show();
    }

    private void importJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            toast("内容为空");
            return;
        }
        String pluginId = "plugin_" + System.currentTimeMillis();
        PluginManager.Plugin p = PluginManager.parseImport(json, pluginId);
        if (p == null) {
            toast("导入失败：需要合法的 JSON 对象、非空 name，"
                    + "且至少包含 styles 或 engine");
            return;
        }
        store.installPlugin(p);
        toast("已安装插件「" + PluginManager.displayName(p) + "」"
                + (p.styles.isEmpty() ? "" : "，含 " + p.styles.size() + " 个样式"));
    }

    private void showSpecDoc() {
        android.content.Context ctx = requireContext();
        TextView tv = new TextView(ctx);
        tv.setText(PluginImportDoc.SPEC_TEXT);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
        int pad = SettingsViews.dp(ctx, 16);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(tv);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsViews.dp(ctx, 380)));
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("插件规范")
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
                    "hulfix-plugin", PluginImportDoc.EXAMPLE_JSON));
            toast("示例插件已复制，可用「粘贴 JSON 导入」直接导入");
        } catch (Throwable t) {
            toast("复制失败：" + t.getMessage());
        }
    }

    private void toast(String msg) {
        if (getView() != null) {
            Snackbar.make(getView(), msg, Snackbar.LENGTH_LONG).show();
        }
    }
}
