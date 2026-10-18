package com.example.hulfix.overlay;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/**
 * 悬浮窗卡片根容器：<b>以内容层的高度决定卡片高度</b>。
 *
 * <p><b>为什么必须自定义测量</b>：卡片根容器是 {@code WRAP_CONTENT} 的 FrameLayout，
 * 而背景 / scrim / 高光 / 描边等装饰层都是 {@code MATCH_PARENT} 的子 View。
 * WindowManager 对 {@code WRAP_CONTENT} 窗口给出的测量规格是 {@code AT_MOST(整屏高)}，
 * 而普通 {@link View} 的默认 {@code onMeasure} 在 AT_MOST 下会直接返回 specSize，
 * 于是在 wrap_content 容器里一撑就是整屏高度 —— 表现为：</p>
 *
 * <ul>
 *   <li>卡片高度失控（明明只有一行字，却占了大半个屏幕）；</li>
 *   <li>窗口实际尺寸 = 整屏，连带 {@code FLAG_BLUR_BEHIND} 的模糊区域也变成整屏
 *       （"模糊了整个屏幕"）。</li>
 * </ul>
 *
 * <p>本类先按可用宽度精确测量内容层，拿到真实内容高度，再把所有装饰层按
 * {@code EXACTLY(卡片高度)} 测量，最后按内容高度（不低于样式的最小高度）定尺寸。</p>
 */
public final class CardLayout extends FrameLayout {

    /** 内容层（唯一非 MATCH_PARENT 子 View），决定卡片真实高度。 */
    private View mContent;
    /** 卡片最小高度（px），来自 {@link StyleSpec#minHeightDp}。 */
    private int mMinHeightPx;
    /** 宽度兜底（px）：父级给 UNSPECIFIED 时使用。 */
    private int mFallbackWidthPx;

    public CardLayout(Context context) {
        super(context);
    }

    /**
     * 指定内容层与最小高度。必须在 {@code addView(content)} 之后调用。
     *
     * @param content       决定卡片高度的内容层
     * @param minHeightPx   卡片最小高度（px，0 = 不限制）
     * @param fallbackWidth 父级宽度不可测时的兜底宽度（px）
     */
    public void bindContent(View content, int minHeightPx, int fallbackWidth) {
        mContent = content;
        mMinHeightPx = Math.max(0, minHeightPx);
        mFallbackWidthPx = fallbackWidth;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final View content = mContent;
        if (content == null) {
            // 未绑定内容层：退回标准 FrameLayout 行为，避免整块空白
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }

        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int width = widthMode == MeasureSpec.UNSPECIFIED
                ? Math.max(0, mFallbackWidthPx) : MeasureSpec.getSize(widthMeasureSpec);
        final int availH = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                ? Integer.MAX_VALUE >>> 2 : MeasureSpec.getSize(heightMeasureSpec);

        final int padH = getPaddingLeft() + getPaddingRight();
        final int padV = getPaddingTop() + getPaddingBottom();

        // 1) 内容层：宽度精确、高度按可用空间封顶 → 得到卡片"真实需要"的高度
        content.measure(
                MeasureSpec.makeMeasureSpec(Math.max(0, width - padH), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.max(0, availH - padV), MeasureSpec.AT_MOST));
        int contentH = content.getMeasuredHeight();

        final int cardH = Math.max(contentH + padV, mMinHeightPx);

        // 2) 装饰层（背景 / scrim / 高光 / 描边 / 透镜）：按卡片真实尺寸精确测量。
        //    这一步是关键——绝不能沿用 AT_MOST(整屏)，否则又会被撑满。
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child == content || child.getVisibility() == GONE) continue;
            child.measure(
                    MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY));
        }

        setMeasuredDimension(width, cardH);
    }
}
