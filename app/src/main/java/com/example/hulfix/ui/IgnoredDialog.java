package com.example.hulfix.ui;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.hulfix.core.Settings;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 用户忽略列表对话框：被滑动/长按划掉的通知（跨重启持久化）。
 *
 * <p>在冷却期内这些通知会被引擎抑制；可从列表移除以提前解除忽略。</p>
 */
public final class IgnoredDialog extends androidx.fragment.app.DialogFragment {

    public static IgnoredDialog newInstance() {
        return new IgnoredDialog();
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        // 固定高度：对话框 custom view 默认 wrap_content，weight 子项在其中会测得 0 高度
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsViews.dp(requireContext(), 440)));

        MaterialToolbar toolbar = new MaterialToolbar(requireContext());
        toolbar.setTitle("已忽略的通知");
        root.addView(toolbar);

        SettingsStore store = SettingsStore.get(requireContext());
        List<Settings.IgnoredEntry> entries = store.ignoredEntries();

        if (entries.isEmpty()) {
            TextView empty = new TextView(requireContext());
            empty.setText("还没有忽略任何通知");
            empty.setGravity(Gravity.CENTER);
            empty.setAlpha(0.6f);
            root.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            RecyclerView rv = new RecyclerView(requireContext());
            rv.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            rv.setLayoutManager(new LinearLayoutManager(requireContext()));
            rv.setAdapter(new IgnoredAdapter(entries, store));
            root.addView(rv);
        }

        return new MaterialAlertDialogBuilder(requireContext())
                .setView(root)
                .setNegativeButton("关闭", null)
                .setPositiveButton("全部解除", (d, w) -> store.clearIgnored())
                .create();
    }

    private static final class IgnoredAdapter extends RecyclerView.Adapter<IgnoredVH> {

        private final List<Settings.IgnoredEntry> mEntries;
        private final SettingsStore mStore;
        private final SimpleDateFormat mFmt =
                new SimpleDateFormat("MM-dd HH:mm", Locale.US);

        IgnoredAdapter(List<Settings.IgnoredEntry> entries, SettingsStore store) {
            mEntries = entries;
            mStore = store;
        }

        @NonNull
        @Override
        public IgnoredVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout box = new LinearLayout(parent.getContext());
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = SettingsViews.dp(parent.getContext(), 16);
            box.setPadding(pad, SettingsViews.dp(parent.getContext(), 10), pad,
                    SettingsViews.dp(parent.getContext(), 10));
            box.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView title = new TextView(parent.getContext());
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            box.addView(title);
            TextView meta = new TextView(parent.getContext());
            meta.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            meta.setAlpha(0.7f);
            box.addView(meta);

            IgnoredVH vh = new IgnoredVH(box);
            vh.title = title;
            vh.meta = meta;
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull IgnoredVH h, int position) {
            Settings.IgnoredEntry e = mEntries.get(position);
            h.title.setText(e.title == null || e.title.isEmpty() ? "(无标题)" : e.title);
            h.meta.setText(e.pkg + " · " + mFmt.format(new Date(e.atWallMs))
                    + " · 点按解除");
            h.itemView.setOnClickListener(v -> {
                mStore.removeIgnored(e.key);
                mEntries.remove(position);
                notifyItemRemoved(position);
            });
        }

        @Override
        public int getItemCount() {
            return mEntries.size();
        }
    }

    private static final class IgnoredVH extends RecyclerView.ViewHolder {
        TextView title;
        TextView meta;

        IgnoredVH(LinearLayout box) {
            super(box);
        }
    }
}
