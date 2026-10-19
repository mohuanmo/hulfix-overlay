package com.example.hulfix.overlay;

import android.content.Context;

/**
 * 系统强调色解析（Material You 动态取色）。
 *
 * <p>两处使用：</p>
 * <ul>
 *   <li>{@link OverlayRenderer}：样式 accentMode = {@link StyleSpec#ACCENT_SYSTEM} 时，
 *       用系统强调色覆盖调色板的 accent（进度环 / 脉冲点 / 描边等强调元素）；</li>
 *   <li>{@link LensBackdropView}：动态光效的"高光跟随系统强调色"。</li>
 * </ul>
 *
 * <p><b>为什么不用 androidx</b>：本模块的 overlay 包同时跑在 SystemUI 进程（system_app
 * 域，无 AndroidX 依赖），只能走 framework 的 {@code android.R.attr.colorAccent}。
 * 解析失败统一返回 0（调用方保持原色，绝不崩溃）。</p>
 */
public final class AccentColors {

    private AccentColors() {
    }

    /** 系统强调色 ARGB；解析失败返回 0。 */
    @SuppressWarnings("deprecation")
    public static int systemAccent(Context ctx) {
        if (ctx == null) return 0;
        try {
            android.util.TypedValue tv = new android.util.TypedValue();
            android.content.res.Resources.Theme theme = ctx.getTheme();
            if (theme != null && theme.resolveAttribute(android.R.attr.colorAccent, tv, true)) {
                if (tv.resourceId != 0) {
                    return ctx.getResources().getColor(tv.resourceId);
                }
                return tv.data;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** 向白色混合 amount（0..1）：把强调色提亮为适合做镜面高光的颜色。 */
    public static int lighten(int argb, float amount) {
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        float t = Math.max(0f, Math.min(1f, amount));
        r = (int) (r + (255 - r) * t);
        g = (int) (g + (255 - g) * t);
        b = (int) (b + (255 - b) * t);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
