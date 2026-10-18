package com.example.hulfix.overlay;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.example.hulfix.core.Settings;

/**
 * 悬浮窗渲染器：把 {@link StyleSpec}（纯数据）+ 通知内容 + 用户微调（宽/圆角/不透明度缩放）
 * 构建成可加入 WindowManager 的视图树。
 *
 * <p>同一渲染器同时服务两个场景：SystemUI 内的真实悬浮窗，与设置页内的样式预览
 * （预览不经过 WindowManager，仅把 root 放进预览容器）。</p>
 *
 * <p>构建成本已尽量压缩（视图一次性建好、渐变对象复用、无反射），真实悬浮窗还会经
 * {@link OverlayController} 的视图池二次复用。</p>
 */
public final class OverlayRenderer {

    /** 构建结果：控制器/预览共用。 */
    public static final class Build {
        public FrameLayout root;
        public View bgLayer;
        /** 真折射玻璃背景层（仅 liquid 样式且引擎可用时非 null）。 */
        public LensBackdropView lensView;
        public LensUniforms lensUniforms;
        public ImageView iconView;
        public View iconFrame;
        public TextView appNameView;
        public TextView titleView;
        public TextView textView;
        public View pulseDot;
        public RingDrawable ring;
        public View contentLayer;
        public int widthPx;
        public int estimatedHeightPx;
        public float cornerRadiusPx;
        public int accentColor;
        public int textPrimaryColor;
        /** 用户设置的背景不透明度缩放（未打模糊折扣）——模糊挂载失败时恢复用。 */
        public float bgAlphaUserScale = 1f;
        /** 调色板原始背景色（未缩放 alpha）——恢复用。 */
        public int[] bgColorsRaw;

        /**
         * 把背景层 alpha 恢复到"无模糊"设计值。
         *
         * <p>有界背景模糊挂载失败（个别 ROM 反射不可达）时由控制器调用：渲染阶段
         * 已按"模糊会透出"预降了背景层不透明度，若不恢复，卡片将半透明地直接浮
         * 在桌面上，文字可读性受损。</p>
         */
        public void restoreBgAlpha() {
            if (bgColorsRaw == null) return;
            if (lensView != null) {
                lensView.setBackdropColors(bgColorsRaw, bgAlphaUserScale);
            } else if (bgLayer != null
                    && bgLayer.getBackground() instanceof GradientDrawable) {
                int[] colors = new int[bgColorsRaw.length];
                for (int i = 0; i < colors.length; i++) {
                    colors[i] = scaleAlpha(bgColorsRaw[i], bgAlphaUserScale);
                }
                ((GradientDrawable) bgLayer.getBackground()).setColors(colors);
            }
        }
    }

    /**
     * 模糊激活时背景层不透明度折扣：让卡片后方的系统模糊透出来形成"霜面"。
     *
     * <p>0.55 ≈ 默认 85% 的 tonal 底色降到约 47%——既可见磨砂，又保留文字可读性；
     * 用户仍可用"背景不透明度"滑块整体回调。</p>
     */
    private static final float BLUR_BG_DISCOUNT = 0.55f;

    /**
     * 当前样式 + 设置是否走 AGSL 真折射路径。
     *
     * <p>判定条件（三者同时满足）：样式为液态玻璃类、用户开关打开、设备支持 API 33+。
     * 不满足时渲染回退到传统模拟路径（GradientDrawable + BlurBehind + 双描边），
     * 与旧版行为完全一致。着色器运行时编译失败由 {@link LensBackdropView} 自行降级。</p>
     */
    public static boolean lensActive(StyleSpec spec, Settings settings) {
        return settings.lensEnabled
                && spec.variant.equals(StyleSpec.VARIANT_LIQUID)
                && LensEngine.isSupported();
    }

    /**
     * 是否启用<b>有界</b>窗口背景模糊（磨砂玻璃）。
     *
     * <p>这是"模糊只出现在卡片上"的唯一正确机制：经 {@code ViewRootImpl
     * #createBackgroundBlurDrawable()} 创建 {@code BackgroundBlurDrawable}，其边界即
     * 模糊区域，由窗口自己的 Aggregator 汇总成 {@code setBlurRegions} 送到本窗口
     * surface——绝不波及卡片以外的屏幕。</p>
     *
     * <p><b>切勿改回 {@code FLAG_BLUR_BEHIND}</b>：该 flag 在 WMS 里走 Dimmer
     * （{@code WindowState#applyDims → Dimmer#adjustAppearance}），是在窗口背后创建
     * 一张覆盖整个 DisplayArea 的全屏模糊层，官方语义即 "blur the whole screen behind
     * the window"，与卡片大小无关。</p>
     */
    public static boolean windowBlurActive(Context ctx, StyleSpec spec, Settings settings) {
        if (spec == null || spec.blurRadiusDp <= 0f) return false;
        if (android.os.Build.VERSION.SDK_INT < 31) return false;
        try {
            android.view.WindowManager wm =
                    (android.view.WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            // 省电模式 / 低端 GPU / 开发者选项可能全局关闭跨窗口模糊
            return wm != null && wm.isCrossWindowBlurEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 由设置计算真折射 uniform 包（rebind 时重复调用以应用滑块最新值）。
     * 相位按样式 id 稳定派生：每条样式一个固定呼吸相位，不随时间刷新。
     */
    public static LensUniforms lensUniformsFor(Context ctx, StyleSpec spec, Settings settings,
                                               float cornerPx, int highlightArgb) {
        float density = ctx.getResources().getDisplayMetrics().density;
        float edgeWidthPx = Math.max(dp(ctx, 8f), cornerPx * 0.35f);
        int hash = spec.id != null ? spec.id.hashCode() : 0;
        float phase = ((hash & 0xFFFF) / 65535f) * (float) (Math.PI * 2.0);
        return LensUniforms.create(
                cornerPx,
                edgeWidthPx,
                settings.lensThickness * density,
                settings.lensEdge,
                settings.lensDispersion,
                settings.lensSpecular,
                settings.lensIOR,
                highlightArgb,
                phase);
    }

    public static Build build(Context ctx, StyleSpec spec, Settings settings, boolean dark,
                              CharSequence appName, CharSequence title, CharSequence text,
                              Icon icon, int accentOverride) {
        Build b = new Build();
        float density = ctx.getResources().getDisplayMetrics().density;
        float scaledDensity = ctx.getResources().getDisplayMetrics().scaledDensity;
        float sw = ctx.getResources().getDisplayMetrics().widthPixels;

        StyleSpec.Palette pal = dark ? spec.dark : spec.light;
        // 有界窗口模糊启用时，背景整体再降一档透明度，让卡片下方的霜感透出来
        // （磨砂玻璃 = 霜面 + 薄 tint；全不透光就只剩一块纯色板）。
        boolean windowBlur = windowBlurActive(ctx, spec, settings);
        float bgScale = clamp(settings.bgAlpha, 0.15f, 1f);
        // 记录未折扣的设计值：模糊 Drawable 挂载失败时 restoreBgAlpha() 靠它恢复
        b.bgAlphaUserScale = bgScale;
        b.bgColorsRaw = pal.bgColors;
        if (windowBlur) bgScale *= BLUR_BG_DISCOUNT;

        int accent = spec.accentMode == StyleSpec.ACCENT_DYNAMIC && accentOverride != 0
                ? accentOverride : pal.accent;

        // ---------- 尺寸 ----------
        int widthPx = (int) (Math.min(sw * 0.40f, 340f * density) * clamp(settings.widthScale, 0.4f, 1.6f));
        float cornerPx = spec.cornerRadiusDp * density * clamp(settings.cornerScale, 0f, 2.5f);
        int padH = (int) (spec.paddingHDp * density);
        int padV = (int) (spec.paddingVDp * density);
        int iconSize = (int) (spec.iconSizeDp * density);
        int iconPad = (int) (spec.iconPaddingDp * density);

        b.widthPx = widthPx;
        b.cornerRadiusPx = cornerPx;
        b.accentColor = accent;
        b.textPrimaryColor = pal.textPrimary;

        // ---------- 根容器 ----------
        // 用 CardLayout 而非裸 FrameLayout：裸 FrameLayout 在 wrap_content 下会被
        // MATCH_PARENT 的背景层撑成整屏高度（卡片占大半屏 + 模糊铺满全屏）。
        CardLayout root = new CardLayout(ctx);
        root.setLayoutParams(new FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setClipToOutline(true);
        root.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cornerPx);
            }
        });
        b.root = root;

        // ---------- 背景层 ----------
        if (lensActive(spec, settings)) {
            // 真折射路径：玻璃背景层（内容经 AGSL 物理折射后呈现）
            // 注意：传未缩放的颜色数组 + 缩放因子，由 View 内部统一应用（避免双重缩放）
            LensBackdropView lens = new LensBackdropView(ctx);
            lens.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            lens.setBackdropColors(pal.bgColors, bgScale);
            lens.setAccentColor(accent);
            LensUniforms lensU = lensUniformsFor(ctx, spec, settings, cornerPx, pal.highlightColor);
            lens.setLensUniforms(lensU);
            root.addView(lens);
            b.bgLayer = lens;
            b.lensView = lens;
            b.lensUniforms = lensU;
        } else {
            // 传统路径：纯渐变背景（降级时与原行为完全一致）
            int[] bg = scaleAlphas(pal.bgColors, bgScale);
            GradientDrawable bgd = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, bg);
            bgd.setCornerRadius(cornerPx);
            View bgLayer = new View(ctx);
            bgLayer.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            bgLayer.setBackground(bgd);
            root.addView(bgLayer);
            b.bgLayer = bgLayer;
        }

        // ---------- scrim 罩层（玻璃压暗/提亮） ----------
        int scrim = scaleAlpha(spec.scrimColor, bgScale);
        if ((scrim >>> 24) != 0) {
            View scrimView = new View(ctx);
            scrimView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            scrimView.setBackgroundColor(scrim);
            root.addView(scrimView);
        }

        // ---------- 顶部镜面高光 ----------
        if (spec.topHighlight) {
            int hlH = dp(ctx, spec.variant.equals(StyleSpec.VARIANT_LIQUID) ? 44f : 28f);
            View hl = new View(ctx);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, hlH);
            lp.gravity = Gravity.TOP;
            hl.setLayoutParams(lp);
            GradientDrawable hld = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{withAlpha(pal.highlightColor, a(pal.highlightColor)), 0x00000000});
            hld.setCornerRadii(new float[]{cornerPx, cornerPx, cornerPx, cornerPx, 0, 0, 0, 0});
            hl.setBackground(hld);
            root.addView(hl);
        }

        // ---------- 底部接触阴影 ----------
        if (spec.bottomShadow) {
            View sh = new View(ctx);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 4f));
            lp.gravity = Gravity.BOTTOM;
            sh.setLayoutParams(lp);
            GradientDrawable shd = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{0x00000000, withAlpha(pal.shadowColor, a(pal.shadowColor))});
            shd.setCornerRadii(new float[]{0, 0, 0, 0, cornerPx, cornerPx, cornerPx, cornerPx});
            sh.setBackground(shd);
            root.addView(sh);
        }

        // ---------- 描边 ----------
        if (spec.strokeWidthDp > 0.1f) {
            if (spec.variant.equals(StyleSpec.VARIANT_NEON)) {
                // 霓虹：渐变扫掠描边
                View neon = new View(ctx);
                neon.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                neon.setBackground(new NeonStrokeDrawable(cornerPx,
                        spec.strokeWidthDp * density, accent));
                root.addView(neon);
            } else {
                View st = new View(ctx);
                st.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                GradientDrawable std = new GradientDrawable();
                std.setShape(GradientDrawable.RECTANGLE);
                std.setCornerRadius(cornerPx);
                std.setStroke((int) (spec.strokeWidthDp * density),
                        withAlpha(pal.strokeColor, a(pal.strokeColor)));
                st.setBackground(std);
                root.addView(st);
            }
        }

        // ---------- 内描边（液态玻璃双层折射） ----------
        if (spec.doubleStroke) {
            View inner = new View(ctx);
            int inset = Math.max(1, (int) (spec.strokeWidthDp * density));
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            lp.setMargins(inset, inset, inset, inset);
            inner.setLayoutParams(lp);
            GradientDrawable idd = new GradientDrawable();
            idd.setShape(GradientDrawable.RECTANGLE);
            idd.setCornerRadius(Math.max(0f, cornerPx - inset));
            idd.setStroke(1, withAlpha(pal.highlightColor, a(pal.highlightColor) / 2));
            inner.setBackground(idd);
            root.addView(inner);
        }

        // ---------- 内容层 ----------
        LinearLayout content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.HORIZONTAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(padH, padV, padH, padV);
        content.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        b.contentLayer = content;

        // 图标容器
        int iconBox = iconSize + iconPad * 2;
        if (spec.progressRing) iconBox += dp(ctx, 6f); // 环外扩空间
        FrameLayout iconFrame = new FrameLayout(ctx);
        LinearLayout.LayoutParams iconFrameLp = new LinearLayout.LayoutParams(iconBox, iconBox);
        iconFrameLp.gravity = Gravity.CENTER_VERTICAL;
        iconFrame.setLayoutParams(iconFrameLp);
        if (spec.iconCircleBg) {
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(withAlpha(pal.iconBgColor, a(pal.iconBgColor)));
            circle.setStroke(Math.max(1, (int) density), withAlpha(pal.iconStrokeColor, a(pal.iconStrokeColor)));
            iconFrame.setBackground(circle);
        }

        ImageView iconView = new ImageView(ctx);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(iconSize, iconSize);
        iconLp.gravity = Gravity.CENTER;
        iconView.setLayoutParams(iconLp);
        if (icon != null) iconView.setImageIcon(icon);
        iconFrame.addView(iconView);
        b.iconView = iconView;
        b.iconFrame = iconFrame;

        // 倒计时环（覆盖在图标容器上）
        if (spec.progressRing) {
            RingDrawable ring = new RingDrawable();
            int rw = Math.max(2, (int) (1.5f * density));
            ring.setStrokeWidthPx(rw);
            ring.setColors(withAlpha(pal.ringTrackColor, a(pal.ringTrackColor)), pal.ringColor);
            View ringView = new View(ctx);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            ringView.setLayoutParams(rlp);
            ringView.setBackground(ring);
            iconFrame.addView(ringView);
            b.ring = ring;
        }

        content.addView(iconFrame);

        // 文本栈
        LinearLayout textStack = new LinearLayout(ctx);
        textStack.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tsLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tsLp.setMargins(dp(ctx, 14f), 0, 0, 0);
        tsLp.gravity = Gravity.CENTER_VERTICAL;
        textStack.setLayoutParams(tsLp);

        // 注意：appName / text 视图必须无条件创建——视图池传入的是空内容，
        // 若以“内容非空”为创建条件，复用后这两个视图恒为 null，应用名与正文将永远不显示。
        boolean showApp = spec.showAppName && settings.showAppName && !TextUtils.isEmpty(appName);
        TextView appNameView = new TextView(ctx);
        appNameView.setText(appName);
        appNameView.setTextColor(withAlpha(pal.appNameColor, a(pal.appNameColor)));
        appNameView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, spec.appNameSizeSp);
        appNameView.setMaxLines(1);
        appNameView.setEllipsize(TextUtils.TruncateAt.END);
        appNameView.setVisibility(showApp ? View.VISIBLE : View.GONE);
        textStack.addView(appNameView);
        b.appNameView = appNameView;

        TextView titleView = new TextView(ctx);
        titleView.setText(title);
        titleView.setTextColor(withAlpha(pal.textPrimary, a(pal.textPrimary)));
        titleView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, spec.titleSizeSp);
        titleView.setTypeface(titleView.getTypeface(), android.graphics.Typeface.BOLD);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textStack.addView(titleView);
        b.titleView = titleView;

        TextView textView = new TextView(ctx);
        textView.setText(text);
        textView.setTextColor(withAlpha(pal.textSecondary, a(pal.textSecondary)));
        textView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, spec.textSizeSp);
        textView.setMaxLines(Math.max(1, settings.maxTextLines));
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setVisibility(TextUtils.isEmpty(text) ? View.GONE : View.VISIBLE);
        textStack.addView(textView);
        b.textView = textView;
        content.addView(textStack);

        // 脉冲点
        if (spec.pulseDot) {
            View dot = new View(ctx);
            int ds = dp(ctx, 8f);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ds, ds);
            dlp.gravity = Gravity.CENTER_VERTICAL;
            dlp.setMargins(dp(ctx, 8f), 0, dp(ctx, 2f), 0);
            dot.setLayoutParams(dlp);
            GradientDrawable dd = new GradientDrawable();
            dd.setShape(GradientDrawable.OVAL);
            dd.setColor(pal.pulseColor);
            dot.setBackground(dd);
            content.addView(dot);
            b.pulseDot = dot;
        }

        root.addView(content);
        // 卡片高度 = 内容层高度（装饰层按此高度精确测量），下限取样式最小高度
        root.bindContent(content, (int) (spec.minHeightDp * density), widthPx);

        // ---------- 估算高度（入场动画位移量用；WRAP 实际高度以布局为准） ----------
        // 视图恒在（含 GONE），按最坏情况估算：应用名可见 + 正文满行
        float lineH = spec.titleSizeSp * 1.4f;
        float lineH2 = spec.textSizeSp * 1.35f;
        float stackH = (spec.showAppName && settings.showAppName
                ? spec.appNameSizeSp * 1.35f + dp(ctx, 2f) : 0f)
                + lineH + lineH2 * Math.max(1, settings.maxTextLines) + dp(ctx, 2f);
        float contentH = Math.max(iconBox, stackH) + padV * 2;
        b.estimatedHeightPx = Math.max((int) contentH, (int) (spec.minHeightDp * density));
        return b;
    }

    // ==================== 工具 ====================

    public static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static int a(int argb) {
        return (argb >>> 24) & 0xFF;
    }

    private static int withAlpha(int argb, int alpha) {
        return (alpha & 0xFF) << 24 | (argb & 0x00FFFFFF);
    }

    private static int scaleAlpha(int argb, float f) {
        return withAlpha(argb, (int) (a(argb) * f));
    }

    private static int[] scaleAlphas(int[] arr, float f) {
        int[] out = new int[arr.length];
        for (int i = 0; i < arr.length; i++) out[i] = scaleAlpha(arr[i], f);
        return out;
    }
}
