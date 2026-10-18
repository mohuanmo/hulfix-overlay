package com.example.hulfix.overlay;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * 霓虹脉冲样式的渐变扫掠描边。
 *
 * <p>用水平渐变模拟"发光描边"：中段最亮（accent），向两端渐隐，营造能量沿边框
 * 流动的观感。比 SweepGradient 成本更低且在任何密度下都稳定。</p>
 */
public final class NeonStrokeDrawable extends android.graphics.drawable.Drawable {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mCornerRadius;
    private final float mStrokeWidth;
    private final int mAccent;
    private final RectF mRect = new RectF();

    public NeonStrokeDrawable(float cornerRadius, float strokeWidth, int accent) {
        mCornerRadius = cornerRadius;
        mStrokeWidth = strokeWidth;
        mAccent = accent;
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(strokeWidth);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        RectF b = mRect;
        b.set(getBounds());
        float inset = mStrokeWidth / 2f;
        b.inset(inset, inset);
        if (b.width() <= 0f || b.height() <= 0f) return;

        int mid = mAccent;
        int edge = Color.argb((Color.alpha(mAccent) * 2) / 5, Color.red(mAccent),
                Color.green(mAccent), Color.blue(mAccent));
        LinearGradient shader = new LinearGradient(
                b.left, b.top, b.right, b.bottom,
                new int[]{edge, mid, edge},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP);
        mPaint.setShader(shader);
        canvas.drawRoundRect(b, mCornerRadius, mCornerRadius, mPaint);
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
