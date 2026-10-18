package com.example.hulfix.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;

/**
 * 9 宫格屏幕锚点选择器：点选悬浮窗停靠的屏幕区域。
 *
 * <p>用 16:9 比例的示意屏呈现，选区内置文字标签；纯代码构建，任意屏幕尺寸下
 * 均按比例缩放，不会偏移。</p>
 */
public class PositionPadView extends FrameLayout {

    public interface OnAnchorChange {
        void onChanged(int anchor);
    }

    private static final int[][] LABELS = {
            {0, 1, 2}, {3, 4, 5}, {6, 7, 8}
    };
    private static final String[] NAMES = {
            "左上", "上", "右上",
            "左", "中", "右",
            "左下", "下", "右下"
    };

    private int mAnchor = 2;
    private OnAnchorChange mListener;
    private final View[] mCells = new View[9];

    public PositionPadView(Context context) {
        super(context);
        init();
    }

    public PositionPadView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PositionPadView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        MaterialCardView screen = new MaterialCardView(getContext());
        screen.setCardElevation(0);
        screen.setRadius(dp(16));
        screen.setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceVariant));
        FrameLayout.LayoutParams screenLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        screenLp.height = dp(180);
        addView(screen, screenLp);

        GridLayout grid = new GridLayout(getContext());
        grid.setRowCount(3);
        grid.setColumnCount(3);
        screen.addView(grid, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                int index = LABELS[r][c];
                MaterialCardView cell = new MaterialCardView(getContext());
                cell.setCardElevation(0);
                cell.setRadius(dp(12));
                GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
                glp.width = 0;
                glp.height = 0;
                glp.rowSpec = GridLayout.spec(r, 1f);
                glp.columnSpec = GridLayout.spec(c, 1f);
                glp.setMargins(dp(5), dp(5), dp(5), dp(5));
                cell.setLayoutParams(glp);

                TextView label = new TextView(getContext());
                label.setText(NAMES[index]);
                label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12);
                label.setGravity(Gravity.CENTER);
                cell.addView(label, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                cell.setOnClickListener(v -> setAnchor(index, true));
                grid.addView(cell);
                mCells[index] = cell;
            }
        }
        applySelection();
    }

    public void setAnchor(int anchor) {
        setAnchor(anchor, false);
    }

    public void setAnchor(int anchor, boolean notify) {
        mAnchor = anchor;
        applySelection();
        if (notify && mListener != null) mListener.onChanged(anchor);
    }

    public int getAnchor() {
        return mAnchor;
    }

    public void setOnAnchorChange(OnAnchorChange l) {
        mListener = l;
    }

    private void applySelection() {
        for (int i = 0; i < 9; i++) {
            MaterialCardView cell = (MaterialCardView) mCells[i];
            boolean selected = i == mAnchor;
            cell.setCardBackgroundColor(selected
                    ? resolveColor(com.google.android.material.R.attr.colorPrimary)
                    : resolveColor(com.google.android.material.R.attr.colorSurface));
            TextView label = (TextView) cell.getChildAt(0);
            if (label != null) {
                label.setTextColor(selected
                        ? resolveColor(com.google.android.material.R.attr.colorOnPrimary)
                        : resolveColor(com.google.android.material.R.attr.colorOnSurface));
                label.setAlpha(selected ? 1f : 0.5f);
            }
            cell.setStrokeWidth(selected ? 0 : dp(1));
            if (!selected) {
                cell.setStrokeColor(resolveColor(com.google.android.material.R.attr.colorOutline));
            }
        }
    }

    private int resolveColor(int attr) {
        android.util.TypedValue tv = new android.util.TypedValue();
        getContext().getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
