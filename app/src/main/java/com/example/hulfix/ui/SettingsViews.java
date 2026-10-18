package com.example.hulfix.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import java.util.List;

/**
 * M3 Expressive 风格设置项的代码化构建工具。
 *
 * <p>统一视觉规范：20dp 圆角卡片、轻阴影、段落标题加粗、分组留白。</p>
 *
 * <p><b>平板适配</b>：所有内容容器经 {@link #wrapCentered} 限制最大宽度（640dp）并水平
 * 居中——避免平板/折叠屏长宽比下文字行过长、控件被拉伸偏移。滑块/开关/分段按钮全部
 * 使用 MATCH_PARENT + 固定 dp 边距，不依赖绝对定位。</p>
 */
public final class SettingsViews {

    /** 平板/大屏内容最大宽度（dp）。 */
    public static final int CONTENT_MAX_WIDTH_DP = 640;

    private SettingsViews() {
    }

    // ==================== 容器 ====================

    /** 创建纵向设置容器（放入 NestedScrollView）。 */
    public static LinearLayout createContainer(Context ctx) {
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(ctx, 16);
        root.setPadding(pad, dp(ctx, 4), pad, dp(ctx, 32));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    /**
     * 平板适配核心：把内容限宽居中。
     * 小屏（手机）时等同于普通 MATCH_PARENT；大屏（平板/折叠屏展开）时内容不再横向拉伸。
     * 宽度取 min(640dp, 屏幕像素宽)，避免窄屏手机被固定宽度裁切。
     */
    public static View wrapCentered(Context ctx, View content) {
        FrameLayout frame = new FrameLayout(ctx);
        int maxW = dp(ctx, CONTENT_MAX_WIDTH_DP);
        int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.min(maxW, screenW), ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        frame.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(content, lp);
        return frame;
    }

    // ==================== 卡片基 ====================

    private static MaterialCardView newCard(Context ctx) {
        MaterialCardView card = new MaterialCardView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(ctx, 8));
        card.setLayoutParams(lp);
        card.setCardElevation(dp(ctx, 1));
        card.setRadius(dp(ctx, 20));
        return card;
    }

    // ==================== 段落标题 ====================

    public static TextView addSectionHeader(LinearLayout parent, String title) {
        Context ctx = parent.getContext();
        TextView tv = new TextView(ctx);
        tv.setText(title);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(tv.getTypeface(), Typeface.BOLD);
        tv.setPadding(dp(ctx, 4), dp(ctx, 18), dp(ctx, 4), dp(ctx, 8));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(tv);
        return tv;
    }

    /** 说明文字（弱化）。 */
    public static void addNote(LinearLayout parent, String text) {
        Context ctx = parent.getContext();
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setAlpha(0.7f);
        tv.setPadding(dp(ctx, 6), 0, dp(ctx, 6), dp(ctx, 8));
        parent.addView(tv);
    }

    // ==================== 开关项 ====================

    public interface OnBooleanChange {
        void onChanged(boolean value);
    }

    public static MaterialSwitch addSwitchItem(LinearLayout parent, String title, String summary,
                                               boolean checked, OnBooleanChange listener) {
        Context ctx = parent.getContext();
        MaterialCardView card = newCard(ctx);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));

        LinearLayout textBox = new LinearLayout(ctx);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView titleTv = new TextView(ctx);
        titleTv.setText(title);
        titleTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        textBox.addView(titleTv);

        if (summary != null && !summary.isEmpty()) {
            TextView subTv = new TextView(ctx);
            subTv.setText(summary);
            subTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            subTv.setAlpha(0.7f);
            subTv.setPadding(0, dp(ctx, 2), 0, 0);
            textBox.addView(subTv);
        }
        row.addView(textBox);

        MaterialSwitch sw = new MaterialSwitch(ctx);
        sw.setChecked(checked);
        sw.setClickable(false);
        sw.setFocusable(false);
        if (listener != null) {
            card.setOnClickListener(v -> {
                sw.setChecked(!sw.isChecked());
                listener.onChanged(sw.isChecked());
            });
        }
        row.addView(sw);
        card.addView(row);
        parent.addView(card);
        return sw;
    }

    // ==================== 滑块项 ====================

    public interface OnFloatChange {
        void onChanged(float value);
    }

    public static Slider addSliderItem(LinearLayout parent, String title,
                                       float min, float max, float step, float value,
                                       String unit, OnFloatChange listener) {
        Context ctx = parent.getContext();
        MaterialCardView card = newCard(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 4));

        LinearLayout labelRow = new LinearLayout(ctx);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView titleTv = new TextView(ctx);
        titleTv.setText(title);
        titleTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        labelRow.addView(titleTv, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView valueTv = new TextView(ctx);
        valueTv.setTypeface(valueTv.getTypeface(), Typeface.BOLD);
        valueTv.setGravity(Gravity.END);
        valueTv.setText(formatValue(value, unit));
        labelRow.addView(valueTv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(labelRow);

        Slider slider = new Slider(ctx);
        slider.setValueFrom(min);
        slider.setValueTo(max);
        if (step > 0) slider.setStepSize(step);
        slider.setValue(clampf(value, min, max));
        slider.setLabelFormatter(v -> formatValue(v, unit));
        if (listener != null) {
            slider.addOnChangeListener((s, v, fromUser) -> {
                if (!fromUser) return;
                valueTv.setText(formatValue(v, unit));
                listener.onChanged(v);
            });
        }
        box.addView(slider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(box);
        parent.addView(card);
        return slider;
    }

    private static String formatValue(float v, String unit) {
        if (unit == null) return String.valueOf(Math.round(v));
        if (unit.equals("%")) return Math.round(v * 100) + "%";
        if (unit.equals("s")) return (v / 1000f) + "s";
        return Math.round(v) + unit;
    }

    // ==================== 分段按钮 ====================

    public interface OnIndexChange {
        void onChanged(int index);
    }

    /** options: 显示文本；checkedIndex: 当前选中；返回 toggle group 便于外部刷新。 */
    public static MaterialButtonToggleGroup addSegmentedItem(LinearLayout parent, String title,
                                                             List<String> options, int checkedIndex,
                                                             OnIndexChange listener) {
        Context ctx = parent.getContext();
        MaterialCardView card = newCard(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(ctx, 16), dp(ctx, 12), dp(ctx, 16), dp(ctx, 12));

        if (title != null && !title.isEmpty()) {
            TextView titleTv = new TextView(ctx);
            titleTv.setText(title);
            titleTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            titleTv.setPadding(0, 0, 0, dp(ctx, 8));
            box.addView(titleTv);
        }

        MaterialButtonToggleGroup group = new MaterialButtonToggleGroup(ctx);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);
        for (int i = 0; i < options.size(); i++) {
            MaterialButton btn = new MaterialButton(ctx, null,
                    R.attr.materialButtonOutlinedStyle);
            btn.setText(options.get(i));
            btn.setId(View.generateViewId());
            btn.setTag(i);
            btn.setMaxLines(1);
            btn.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i < options.size() - 1) blp.setMarginEnd(dp(ctx, 6));
            group.addView(btn, blp);
        }
        if (checkedIndex >= 0 && checkedIndex < group.getChildCount()) {
            group.check(group.getChildAt(checkedIndex).getId());
        }
        if (listener != null) {
            group.addOnButtonCheckedListener((g, buttonId, checked) -> {
                if (!checked) return;
                for (int i = 0; i < g.getChildCount(); i++) {
                    if (g.getChildAt(i).getId() == buttonId) {
                        Object tag = g.getChildAt(i).getTag();
                        if (tag instanceof Integer) listener.onChanged((Integer) tag);
                        return;
                    }
                }
            });
        }
        box.addView(group);
        card.addView(box);
        parent.addView(card);
        return group;
    }

    // ==================== 可点击入口项 ====================

    public interface OnClick {
        void onClick();
    }

    public static View addEntryItem(LinearLayout parent, String title, String summary,
                                    OnClick listener) {
        Context ctx = parent.getContext();
        MaterialCardView card = newCard(ctx);
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14));

        LinearLayout textBox = new LinearLayout(ctx);
        textBox.setOrientation(LinearLayout.VERTICAL);
        textBox.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView titleTv = new TextView(ctx);
        titleTv.setText(title);
        titleTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        textBox.addView(titleTv);
        if (summary != null && !summary.isEmpty()) {
            TextView subTv = new TextView(ctx);
            subTv.setText(summary);
            subTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
            subTv.setAlpha(0.7f);
            subTv.setPadding(0, dp(ctx, 2), 0, 0);
            textBox.addView(subTv);
        }
        row.addView(textBox);

        TextView arrow = new TextView(ctx);
        arrow.setText("›");
        arrow.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
        arrow.setAlpha(0.5f);
        row.addView(arrow);
        card.addView(row);
        if (listener != null) card.setOnClickListener(v -> listener.onClick());
        parent.addView(card);
        return card;
    }

    // ==================== 纯文本卡片（提示/说明） ====================

    public static void addTextCard(LinearLayout parent, String title, String body) {
        Context ctx = parent.getContext();
        MaterialCardView card = newCard(ctx);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 14));
        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(ctx);
            t.setText(title);
            t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
            t.setTypeface(t.getTypeface(), Typeface.BOLD);
            box.addView(t);
        }
        if (body != null && !body.isEmpty()) {
            TextView b = new TextView(ctx);
            b.setText(body);
            b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
            b.setAlpha(0.8f);
            b.setPadding(0, dp(ctx, 4), 0, 0);
            box.addView(b);
        }
        card.addView(box);
        parent.addView(card);
    }

    // ==================== 工具 ====================

    public static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static float clampf(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
