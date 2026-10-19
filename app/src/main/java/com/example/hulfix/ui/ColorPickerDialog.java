package com.example.hulfix.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.example.hulfix.overlay.StyleSpec;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 自绘 HSV + Alpha 取色器对话框。
 *
 * <p><b>为什么自绘</b>：Material Components 不提供取色器，而悬浮窗样式的配色大量使用
 * 半透明色（玻璃罩层、描边、高光）——只支持不透明 RGB 的取色器不够用，必须带 Alpha 通道。</p>
 *
 * <p>结构：SV 方阵（饱和度 × 明度）+ 色相条 + Alpha 条 + 棋盘格底预览 + 十六进制输入，
 * 四者实时联动；"确定"回调最终 ARGB。</p>
 */
public final class ColorPickerDialog {

    public interface Listener {
        void onPicked(int argb);
    }

    private ColorPickerDialog() {
    }

    public static void show(Context ctx, String title, int initialArgb, Listener listener) {
        float[] hsv = new float[3];
        Color.colorToHSV(initialArgb | 0xFF000000, hsv);
        final float[] hue = {hsv[0]};
        final float[] sat = {hsv[1]};
        final float[] val = {hsv[2]};
        final int[] alpha = {initialArgb >>> 24};

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = SettingsViews.dp(ctx, 20);
        root.setPadding(pad, pad, pad, pad);

        // ---------- 预览 + 十六进制 ----------
        LinearLayout topRow = new LinearLayout(ctx);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        final PreviewSwatch swatch = new PreviewSwatch(ctx);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                SettingsViews.dp(ctx, 56), SettingsViews.dp(ctx, 56));
        slp.setMarginEnd(SettingsViews.dp(ctx, 14));
        topRow.addView(swatch, slp);

        final EditText hex = new EditText(ctx);
        hex.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setText(StyleSpec.colorHex(initialArgb));
        hex.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        hex.setHint("#AARRGGBB");
        hex.setSingleLine();
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topRow.addView(hex, hlp);
        root.addView(topRow);

        // ---------- SV 方阵 ----------
        final SvSquare sv = new SvSquare(ctx);
        LinearLayout.LayoutParams svLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, SettingsViews.dp(ctx, 180));
        svLp.topMargin = SettingsViews.dp(ctx, 12);
        root.addView(sv, svLp);

        // ---------- 色相条 ----------
        final HueStrip hueStrip = new HueStrip(ctx);
        LinearLayout.LayoutParams hueLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, SettingsViews.dp(ctx, 32));
        hueLp.topMargin = SettingsViews.dp(ctx, 10);
        root.addView(hueStrip, hueLp);

        // ---------- Alpha 条 ----------
        final AlphaStrip alphaStrip = new AlphaStrip(ctx);
        LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, SettingsViews.dp(ctx, 32));
        aLp.topMargin = SettingsViews.dp(ctx, 10);
        root.addView(alphaStrip, aLp);

        final Runnable sync = new Runnable() {
            @Override
            public void run() {
                int argb = (alpha[0] << 24)
                        | (Color.HSVToColor(new float[]{hue[0], sat[0], val[0]}) & 0x00FFFFFF);
                swatch.setColor(argb);
                sv.setHue(hue[0]);
                sv.setSv(sat[0], val[0]);
                hueStrip.setHue(hue[0]);
                alphaStrip.setColor(argb, alpha[0]);
                if (!hex.isFocused()) hex.setText(StyleSpec.colorHex(argb));
            }
        };
        sv.setOnChangeListener((s, v) -> {
            sat[0] = s;
            val[0] = v;
            sync.run();
        });
        hueStrip.setOnChangeListener(h -> {
            hue[0] = h;
            sync.run();
        });
        alphaStrip.setOnChangeListener(a -> {
            alpha[0] = a;
            sync.run();
        });
        hex.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) sync.run();
        });
        sync.run();

        new MaterialAlertDialogBuilder(ctx)
                .setTitle(title)
                .setView(root)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (d, w) -> {
                    if (listener == null) return;
                    int argb = (alpha[0] << 24)
                            | (Color.HSVToColor(new float[]{hue[0], sat[0], val[0]}) & 0x00FFFFFF);
                    listener.onPicked(argb);
                })
                .setNeutralButton("从十六进制应用", (d, w) -> {
                    if (listener == null) return;
                    int argb = StyleSpec.parseColor(hex.getText().toString(), initialArgb);
                    listener.onPicked(argb);
                })
                .show();
    }

    // ==================== SV 方阵 ====================

    private static final class SvSquare extends View {
        private float mHue = 0f, mSat = 1f, mVal = 1f;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mThumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private OnSvChange mListener;

        interface OnSvChange {
            void onChange(float sat, float val);
        }

        SvSquare(Context ctx) {
            super(ctx);
            mThumb.setStyle(Paint.Style.STROKE);
            mThumb.setStrokeWidth(SettingsViews.dp(ctx, 2.5f));
            mThumb.setColor(0xFFFFFFFF);
        }

        void setHue(float h) {
            if (h == mHue) return;
            mHue = h;
            invalidate();
        }

        void setSv(float s, float v) {
            mSat = s;
            mVal = v;
            invalidate();
        }

        void setOnChangeListener(OnSvChange l) {
            mListener = l;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            int hueColor = Color.HSVToColor(new float[]{mHue, 1f, 1f});
            mPaint.setShader(null);
            mPaint.setColor(hueColor);
            canvas.drawRect(0, 0, w, h, mPaint);
            // 水平：白 → 纯色（饱和度）
            mPaint.setShader(new LinearGradient(0, 0, w, 0,
                    0xFFFFFFFF, hueColor & 0x00FFFFFF, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, mPaint);
            // 垂直：透明 → 黑（明度）
            mPaint.setShader(new LinearGradient(0, 0, 0, h,
                    0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, mPaint);
            mPaint.setShader(null);
            // 当前值圆环
            float cx = mSat * w;
            float cy = (1f - mVal) * h;
            mThumb.setColor(0xFFFFFFFF);
            canvas.drawCircle(cx, cy, SettingsViews.dp(getContext(), 9), mThumb);
            mThumb.setColor(0x80000000);
            canvas.drawCircle(cx, cy, SettingsViews.dp(getContext(), 9) + 1.5f, mThumb);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (mListener == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_MOVE) {
                float s = clamp01(event.getX() / Math.max(1, getWidth()));
                float v = 1f - clamp01(event.getY() / Math.max(1, getHeight()));
                mListener.onChange(s, v);
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    // ==================== 色相条 ====================

    private static final class HueStrip extends View {
        private float mHue = 0f;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private OnHueChange mListener;

        interface OnHueChange {
            void onChange(float hue);
        }

        HueStrip(Context ctx) {
            super(ctx);
            mPaint.setShader(new LinearGradient(0, 0, 0, 100, new int[]{
                    0xFFFF0000, 0xFFFFFF00, 0xFF00FF00,
                    0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000,
            }, null, Shader.TileMode.CLAMP));
        }

        void setHue(float h) {
            mHue = h;
            invalidate();
        }

        void setOnChangeListener(OnHueChange l) {
            mListener = l;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
            float y = (mHue / 360f) * getHeight();
            mPaint.setShader(null);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(SettingsViews.dp(getContext(), 2.5f));
            mPaint.setColor(0xFFFFFFFF);
            canvas.drawLine(0, y, getWidth(), y, mPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (mListener == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_MOVE) {
                float h = clamp01(event.getY() / Math.max(1, getHeight())) * 360f;
                mListener.onChange(h);
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    // ==================== Alpha 条 ====================

    private static final class AlphaStrip extends View {
        private int mAlpha = 255;
        private int mRgb;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private OnAlphaChange mListener;

        interface OnAlphaChange {
            void onChange(int alpha);
        }

        AlphaStrip(Context ctx) {
            super(ctx);
        }

        void setColor(int argb, int alpha) {
            mRgb = argb | 0xFF000000;
            mAlpha = alpha;
            mPaint.setShader(new LinearGradient(0, 0, getWidth(), 0,
                    mRgb & 0x00FFFFFF, mRgb, Shader.TileMode.CLAMP));
            invalidate();
        }

        void setOnChangeListener(OnAlphaChange l) {
            mListener = l;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            // 棋盘格：透出半透明色背后的"内容"
            int cell = SettingsViews.dp(getContext(), 8);
            mPaint.setShader(null);
            for (int y = 0; y < h; y += cell) {
                for (int x = 0; x < w; x += cell) {
                    boolean dark = ((x / cell) + (y / cell)) % 2 == 0;
                    mPaint.setColor(dark ? 0xFFBBBBBB : 0xFFEEEEEE);
                    canvas.drawRect(x, y, Math.min(x + cell, w), Math.min(y + cell, h), mPaint);
                }
            }
            mPaint.setShader(new LinearGradient(0, 0, w, 0,
                    mRgb & 0x00FFFFFF, mRgb, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, mPaint);
            mPaint.setShader(null);
            float x = (mAlpha / 255f) * w;
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(SettingsViews.dp(getContext(), 2.5f));
            mPaint.setColor(0xFFFFFFFF);
            canvas.drawLine(x, 0, x, h, mPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (mListener == null) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN
                    || action == MotionEvent.ACTION_MOVE) {
                int a = (int) (clamp01(event.getX() / Math.max(1, getWidth())) * 255);
                mListener.onChange(a);
                return true;
            }
            return super.onTouchEvent(event);
        }
    }

    // ==================== 预览色块（棋盘格底） ====================

    private static final class PreviewSwatch extends View {
        private int mColor = 0xFF000000;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        PreviewSwatch(Context ctx) {
            super(ctx);
            android.graphics.drawable.GradientDrawable border =
                    new android.graphics.drawable.GradientDrawable();
            border.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            border.setCornerRadius(SettingsViews.dp(ctx, 12));
            border.setStroke(SettingsViews.dp(ctx, 1), 0x66000000);
            setBackground(border);
            setClipToOutline(true);
        }

        void setColor(int argb) {
            mColor = argb;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            int cell = SettingsViews.dp(getContext(), 8);
            for (int y = 0; y < h; y += cell) {
                for (int x = 0; x < w; x += cell) {
                    boolean dark = ((x / cell) + (y / cell)) % 2 == 0;
                    mPaint.setColor(dark ? 0xFFBBBBBB : 0xFFEEEEEE);
                    canvas.drawRect(x, y, Math.min(x + cell, w), Math.min(y + cell, h), mPaint);
                }
            }
            mPaint.setColor(mColor);
            canvas.drawRect(0, 0, w, h, mPaint);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
