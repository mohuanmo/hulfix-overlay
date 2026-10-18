package com.example.hulfix.overlay;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * 圆环进度 Drawable：围绕应用图标的自动消失倒计时环。
 *
 * <p>track 为底环，progress 从顶部（-90°）顺时针扫过；cap 为圆头，视觉柔和。
 * 仅 {@link StyleSpec#progressRing} 样式（霓虹脉冲）默认启用，其他样式可通过
 * 自定义 JSON 打开。</p>
 */
public final class RingDrawable extends Drawable {

    private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float mProgress = 1f;
    private int mTrackColor = 0x33FFFFFF;
    private int mProgressColor = 0xFF00E5FF;
    private float mStrokeWidthPx = 6f;

    public RingDrawable() {
        mTrackPaint.setStyle(Paint.Style.STROKE);
        mProgressPaint.setStyle(Paint.Style.STROKE);
        mTrackPaint.setStrokeCap(Paint.Cap.ROUND);
        mProgressPaint.setStrokeCap(Paint.Cap.ROUND);
        mTrackPaint.setColor(mTrackColor);
        mProgressPaint.setColor(mProgressColor);
    }

    public void setColors(int trackColor, int progressColor) {
        mTrackColor = trackColor;
        mProgressColor = progressColor;
        mTrackPaint.setColor(trackColor);
        mProgressPaint.setColor(progressColor);
        invalidateSelf();
    }

    public void setStrokeWidthPx(float w) {
        mStrokeWidthPx = w;
        mTrackPaint.setStrokeWidth(w);
        mProgressPaint.setStrokeWidth(w);
        invalidateSelf();
    }

    /** @param progress 0..1 */
    public void setProgress(float progress) {
        float p = Math.max(0f, Math.min(1f, progress));
        if (Math.abs(p - mProgress) < 0.002f) return;
        mProgress = p;
        invalidateSelf();
    }

    public float getProgress() {
        return mProgress;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        float half = mStrokeWidthPx / 2f;
        float cx = b.exactCenterX();
        float cy = b.exactCenterY();
        float radius = Math.min(b.width(), b.height()) / 2f - half;
        if (radius <= 0f) return;

        canvas.drawCircle(cx, cy, radius, mTrackPaint);
        if (mProgress > 0.001f) {
            // 顶部为 0 时刻：剩余时间越多弧越长；随时间推进弧缩短直至归零
            float sweep = 360f * mProgress;
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                    -90f, sweep, false, mProgressPaint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        mTrackPaint.setAlpha(alpha);
        mProgressPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mTrackPaint.setColorFilter(colorFilter);
        mProgressPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
