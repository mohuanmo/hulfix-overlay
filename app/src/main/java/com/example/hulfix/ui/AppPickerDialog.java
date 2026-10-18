package com.example.hulfix.ui;

import android.app.Dialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.Collator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 应用多选对话框（M3 全宽对话框 + 内嵌 RecyclerView + 搜索）。
 *
 * <p>用于黑名单 / 白名单 / 前台抑制列表三处复用；应用列表在后台线程加载，
 * 避免主线程卡顿。对话框宽度同样受限宽居中（平板适配）。</p>
 */
public final class AppPickerDialog extends androidx.fragment.app.DialogFragment {

    public static final String ARG_TITLE = "title";
    public static final String ARG_SELECTED = "selected";
    public static final String ARG_SHOW_SYSTEM = "showSystem";

    public interface OnConfirm {
        void onConfirm(Set<String> selected);
    }

    private final Set<String> mSelected = new HashSet<>();
    private final List<AppEntry> mAll = new ArrayList<>();
    private final List<AppEntry> mShown = new ArrayList<>();
    private AppAdapter mAdapter;
    private boolean mShowSystem;

    public static AppPickerDialog newInstance(String title, Set<String> selected,
                                              boolean showSystem, OnConfirm cb) {
        AppPickerDialog d = new AppPickerDialog();
        d.mConfirm = cb;
        Bundle b = new Bundle();
        b.putString(ARG_TITLE, title);
        b.putStringArrayList(ARG_SELECTED, new ArrayList<>(selected));
        b.putBoolean(ARG_SHOW_SYSTEM, showSystem);
        d.setArguments(b);
        return d;
    }

    private OnConfirm mConfirm;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Bundle args = requireArguments();
        String title = args.getString(ARG_TITLE, "选择应用");
        List<String> initial = args.getStringArrayList(ARG_SELECTED);
        if (initial != null) mSelected.addAll(initial);
        mShowSystem = args.getBoolean(ARG_SHOW_SYSTEM, true);

        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        // 固定高度：对话框 custom view 默认 wrap_content，weight 子项在其中会测得 0 高度
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsViews.dp(requireContext(), 440)));

        MaterialToolbar toolbar = new MaterialToolbar(requireContext());
        toolbar.setTitle(title);
        toolbar.setTitleTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface));
        toolbar.setSubtitle("已选 " + mSelected.size() + " 个");
        toolbar.setSubtitleTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant));
        root.addView(toolbar);

        // 搜索 + 系统应用开关行
        LinearLayout searchRow = new LinearLayout(requireContext());
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        int pad = SettingsViews.dp(requireContext(), 16);
        searchRow.setPadding(pad, 0, pad, 0);
        EditText search = new EditText(requireContext());
        search.setHint("搜索应用名 / 包名");
        search.setSingleLine(true);
        searchRow.addView(search, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        MaterialSwitch sysSwitch = new MaterialSwitch(requireContext());
        sysSwitch.setChecked(mShowSystem);
        sysSwitch.setText("系统应用");
        sysSwitch.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        sysSwitch.setOnCheckedChangeListener((b, checked) -> {
            mShowSystem = checked;
            applyFilter(search.getText().toString());
        });
        searchRow.addView(sysSwitch);
        root.addView(searchRow);

        RecyclerView rv = new RecyclerView(requireContext());
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        mAdapter = new AppAdapter();
        rv.setAdapter(mAdapter);
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        rvLp.setMargins(0, SettingsViews.dp(requireContext(), 8), 0, 0);
        root.addView(rv, rvLp);

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                applyFilter(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        loadApps();

        return new MaterialAlertDialogBuilder(requireContext())
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    if (mConfirm != null) mConfirm.onConfirm(new HashSet<>(mSelected));
                })
                .create();
    }

    private void loadApps() {
        // 用 applicationContext：对话框关闭后 fragment detach，requireContext() 会抛异常
        final android.content.Context appCtx = requireContext().getApplicationContext();
        new Thread(() -> {
            PackageManager pm = appCtx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            List<AppEntry> entries = new ArrayList<>();
            for (ApplicationInfo ai : apps) {
                // 单个应用的 label 解析异常（资源缺失 / 损坏）不应拖垮整个列表加载
                String label;
                try {
                    CharSequence cs = pm.getApplicationLabel(ai);
                    label = cs != null ? cs.toString() : ai.packageName;
                } catch (Throwable t) {
                    label = ai.packageName;
                }
                entries.add(new AppEntry(ai.packageName, label, isSystemApp(ai)));
            }
            // 系统应用置底 + 按本地化规则排序（中文/拼音等正确排序，而非裸 toLowerCase 比较）
            Collator collator = Collator.getInstance(java.util.Locale.getDefault());
            Collections.sort(entries, Comparator
                    .comparing((AppEntry e) -> e.system ? 1 : 0)
                    .thenComparing(e -> e.label, collator));
            new android.os.Handler(Looper.getMainLooper()).post(() -> {
                if (!isAdded()) return;
                mAll.clear();
                mAll.addAll(entries);
                applyFilter("");
            });
        }).start();
    }

    /**
     * 可靠的系统应用判定。
     *
     * <p>仅凭 {@link ApplicationInfo#FLAG_SYSTEM} 不完整：部分 ROM 把系统应用装在 data
     * 分区但 {@code publicSourceDir} 仍指向系统分区，会漏判；且 {@link
     * ApplicationInfo#FLAG_UPDATED_SYSTEM_APP}（系统应用被覆盖更新）也应算系统应用。
     * 这里叠加分区路径前缀兜底。</p>
     */
    private static boolean isSystemApp(ApplicationInfo ai) {
        if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return true;
        if ((ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) return true;
        String dir = ai.publicSourceDir != null ? ai.publicSourceDir : ai.sourceDir;
        if (dir != null) {
            return dir.startsWith("/system")
                    || dir.startsWith("/vendor")
                    || dir.startsWith("/product")
                    || dir.startsWith("/system_ext")
                    || dir.startsWith("/odm")
                    || dir.startsWith("/apex");
        }
        return false;
    }

    private void applyFilter(String query) {
        mShown.clear();
        String q = query == null ? "" : query.trim().toLowerCase();
        for (AppEntry e : mAll) {
            if (!mShowSystem && e.system) continue;
            if (!q.isEmpty()
                    && !e.label.toLowerCase().contains(q)
                    && !e.pkg.toLowerCase().contains(q)) {
                continue;
            }
            mShown.add(e);
        }
        if (mAdapter != null) mAdapter.notifyDataSetChanged();
    }

    private int resolveColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        requireContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    // ==================== 数据与适配器 ====================

    private static final class AppEntry {
        final String pkg;
        final String label;
        final boolean system;

        AppEntry(String pkg, String label, boolean system) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
        }
    }

    private final class AppAdapter extends RecyclerView.Adapter<AppVH> {

        @NonNull
        @Override
        public AppVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            int pad = SettingsViews.dp(requireContext(), 16);
            row.setPadding(pad, SettingsViews.dp(requireContext(), 10), pad,
                    SettingsViews.dp(requireContext(), 10));
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            com.google.android.material.materialswitch.MaterialSwitch sw =
                    new com.google.android.material.materialswitch.MaterialSwitch(requireContext());
            sw.setClickable(false);
            sw.setFocusable(false);
            row.addView(sw);

            return new AppVH(row);
        }

        @Override
        public void onBindViewHolder(@NonNull AppVH holder, int position) {
            AppEntry e = mShown.get(position);
            holder.title.setText(e.label);
            holder.sub.setText(e.pkg + (e.system ? "  ·  系统" : ""));
            holder.sw.setChecked(mSelected.contains(e.pkg));
            holder.itemView.setOnClickListener(v -> {
                if (mSelected.contains(e.pkg)) {
                    mSelected.remove(e.pkg);
                    holder.sw.setChecked(false);
                } else {
                    mSelected.add(e.pkg);
                    holder.sw.setChecked(true);
                }
            });
        }

        @Override
        public int getItemCount() {
            return mShown.size();
        }
    }

    private static final class AppVH extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView sub;
        final com.google.android.material.materialswitch.MaterialSwitch sw;

        AppVH(LinearLayout row) {
            super(row);
            sw = (com.google.android.material.materialswitch.MaterialSwitch) row.getChildAt(0);
            LinearLayout textBox = new LinearLayout(row.getContext());
            textBox.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(SettingsViews.dp(row.getContext(), 12));
            textBox.setLayoutParams(lp);
            title = new TextView(row.getContext());
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            sub = new TextView(row.getContext());
            sub.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            sub.setAlpha(0.6f);
            sub.setMaxLines(1);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textBox.addView(title);
            textBox.addView(sub);
            row.addView(textBox);
        }
    }
}
