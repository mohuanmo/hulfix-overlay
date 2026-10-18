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

import com.example.hulfix.core.CooldownEngine;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 消息台账对话框：展示最近 100 条通知的弹出/抑制历史。
 *
 * <p>每条记录显示：标题、应用包名、弹出次数、被抑制次数、最近一次抑制原因与时间。
 * 这正是"一条消息被系统重复读出多次"时的可视化证据与排查入口。</p>
 */
public final class LedgerDialog extends androidx.fragment.app.DialogFragment {

    public static LedgerDialog newInstance() {
        return new LedgerDialog();
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
        toolbar.setTitle("消息台账");
        root.addView(toolbar);

        RecyclerView rv = new RecyclerView(requireContext());
        rv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(new LedgerAdapter(parseLedger()));
        root.addView(rv);

        return new MaterialAlertDialogBuilder(requireContext())
                .setView(root)
                .setNegativeButton("关闭", null)
                .setPositiveButton("清除台账", (d, w) -> {
                    SettingsStore.get(requireContext()).clearLedger();
                })
                .create();
    }

    private List<Row> parseLedger() {
        List<Row> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(
                    SettingsStore.get(requireContext()).ledgerJson());
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Row r = new Row();
                r.title = o.optString("title", "(无标题)");
                r.pkg = o.optString("pkg", "");
                r.shown = o.optInt("shown", 0);
                r.suppressed = o.optInt("suppressed", 0);
                r.reason = CooldownEngine.reasonLabel(o.optInt("reason", 0));
                long ts = o.optLong("lastEvent", 0);
                r.time = ts > 0 ? fmt.format(new Date(ts)) : "";
                out.add(r);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static final class Row {
        String title;
        String pkg;
        int shown;
        int suppressed;
        String reason;
        String time;
    }

    private static final class LedgerAdapter extends RecyclerView.Adapter<LedgerVH> {

        private final List<Row> mRows;

        LedgerAdapter(List<Row> rows) {
            mRows = rows;
        }

        @NonNull
        @Override
        public LedgerVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout box = new LinearLayout(parent.getContext());
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = SettingsViews.dp(parent.getContext(), 16);
            box.setPadding(pad, SettingsViews.dp(parent.getContext(), 10), pad,
                    SettingsViews.dp(parent.getContext(), 10));
            box.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView title = new TextView(parent.getContext());
            title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
            title.setMaxLines(1);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            box.addView(title);

            TextView meta = new TextView(parent.getContext());
            meta.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
            meta.setAlpha(0.7f);
            box.addView(meta);

            LedgerVH vh = new LedgerVH(box);
            vh.title = title;
            vh.meta = meta;
            return vh;
        }

        @Override
        public void onBindViewHolder(@NonNull LedgerVH h, int position) {
            Row r = mRows.get(position);
            h.title.setText(r.title);
            h.meta.setText(r.pkg + " · 弹 " + r.shown + " 次 · 抑 " + r.suppressed
                    + " 次 · 最近：" + r.reason + " " + r.time);
        }

        @Override
        public int getItemCount() {
            return mRows.size();
        }
    }

    private static final class LedgerVH extends RecyclerView.ViewHolder {
        TextView title;
        TextView meta;

        LedgerVH(LinearLayout box) {
            super(box);
        }
    }
}
