package com.example.hulfix.overlay;

import android.view.View;

import com.example.hulfix.core.MainHandler;
import com.example.hulfix.core.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * 入场 / 退场 / 回弹动画编排。
 *
 * <p>真实悬浮窗（{@link OverlayController}）与设置页样式预览（{@code StylePreviewView}）
 * 共用本类，保证"预览即所得"。所有位移/缩放/透明度动画均走 {@link SpringAnimation}
 * 物理弹簧，stagger 错落入场由 {@link Player} 内的延时链管理，可整体取消。</p>
 */
public final class AnimPresets {

    /** 一组动画的句柄：持有期间可统一取消。 */
    public static final class Player {
        // 主线程 Handler：惰性创建（模块类在 pre-looper 阶段加载，构造期内 new 会 NPE）
        private final MainHandler mHandler = new MainHandler();
        private final List<SpringAnimation> mSprings = new ArrayList<>();
        private final List<Runnable> mScheduled = new ArrayList<>();
        private final List<View> mBreathing = new ArrayList<>();

        public void track(SpringAnimation a) {
            mSprings.add(a);
        }

        public void postDelayed(Runnable r, long delayMs) {
            mScheduled.add(r);
            mHandler.get().postDelayed(r, delayMs);
        }

        public void trackBreathing(View v) {
            mBreathing.add(v);
        }

        public void cancelAll() {
            for (SpringAnimation a : mSprings) {
                a.cancel();
            }
            mSprings.clear();
            for (Runnable r : mScheduled) {
                mHandler.get().removeCallbacks(r);
            }
            mScheduled.clear();
            for (View v : mBreathing) {
                v.animate().cancel();
                v.setAlpha(1f);
            }
            mBreathing.clear();
        }
    }

    /** 动画目标视图集合。 */
    public interface Host {
        View root();

        View icon();

        View appName();

        View title();

        View text();

        View pulse();

        float widthPx();

        float heightPx();
    }

    // ==================== 入场 ====================

    public static void playEnter(Host host, StyleSpec spec, Settings s, Player player,
                                 Runnable onEnd) {
        View root = host.root();
        int type = s.enterAnim == Settings.ANIM_FOLLOW_STYLE ? spec.enterAnim : s.enterAnim;
        int dir = resolveEnterDir(s, spec);
        int dur = Math.max(80, s.enterDurationMs);
        float w = host.widthPx();
        float h = host.heightPx();

        float fromX = 0f, fromY = 0f;
        float fromScale = 1f;
        boolean fade = true;
        switch (type) {
            case Settings.ANIM_SLIDE:
                fromX = dirOffsetX(dir, w);
                fromY = dirOffsetY(dir, h);
                break;
            case Settings.ANIM_SCALE:
                fromScale = 0.72f;
                break;
            case Settings.ANIM_SPRING:
                fromScale = 0.55f;
                fromX = dirOffsetX(dir, w) * 0.35f;
                fromY = dirOffsetY(dir, h) * 0.35f;
                break;
            case Settings.ANIM_FADE:
            default:
                fade = true;
                break;
        }

        if (fade || fromScale != 1f) {
            SpringAnimation alpha = SpringAnimation.of(root, SpringAnimation.PROP_ALPHA, 1f)
                    .setStartValue(0f)
                    .setStiffness(stiffnessFor(dur * 0.6f))
                    .setDampingRatioNoBouncy();
            player.track(alpha);
            alpha.start();
        }
        if (fromX != 0f) {
            SpringAnimation tx = SpringAnimation.of(root, SpringAnimation.PROP_TRANSLATION_X, 0f)
                    .setStartValue(fromX)
                    .setStiffness(stiffnessFor(dur))
                    .setDampingRatio(bounceDamping(s.enterOvershoot, 0.78f));
            player.track(tx);
            tx.start();
        }
        if (fromY != 0f) {
            SpringAnimation ty = SpringAnimation.of(root, SpringAnimation.PROP_TRANSLATION_Y, 0f)
                    .setStartValue(fromY)
                    .setStiffness(stiffnessFor(dur))
                    .setDampingRatio(bounceDamping(s.enterOvershoot, 0.78f));
            player.track(ty);
            ty.start();
        }
        if (fromScale != 1f) {
            float zeta = type == Settings.ANIM_SPRING ? 0.58f : 0.72f;
            zeta = bounceDamping(s.enterOvershoot, zeta);
            for (int prop : new int[]{SpringAnimation.PROP_SCALE_X, SpringAnimation.PROP_SCALE_Y}) {
                SpringAnimation sc = SpringAnimation.of(root, prop, 1f)
                        .setStartValue(fromScale)
                        .setStiffness(stiffnessFor(dur))
                        .setDampingRatio(zeta);
                player.track(sc);
                sc.start();
            }
        }

        // 内容错落入场
        if (s.staggerContent) {
            staggerIn(player, host.icon(), 40, 0.35f, 1f);
            staggerFadeIn(player, host.appName(), 70, 14f);
            staggerFadeIn(player, host.title(), 95, 10f);
            staggerFadeIn(player, host.text(), 120, 8f);
        }

        // 脉冲点呼吸（霓虹样式）
        View pulse = host.pulse();
        if (pulse != null) {
            player.trackBreathing(pulse);
            pulse.animate()
                    .alpha(0.35f)
                    .setDuration(900)
                    .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                    .withEndAction(() -> {
                        pulse.animate().alpha(1f).setDuration(900).start();
                    })
                    .start();
        }

        if (onEnd != null) {
            long total = dur + (s.staggerContent ? 260 : 60);
            player.postDelayed(onEnd, total);
        }
    }

    // ==================== 退场 ====================

    public static void playExit(Host host, StyleSpec spec, Settings s, Player player,
                                int direction, Runnable onEnd) {
        View root = host.root();
        int type = s.exitAnim == Settings.ANIM_FOLLOW_STYLE ? spec.exitAnim : s.exitAnim;
        int dir = direction >= 0 ? direction : reverseDir(resolveEnterDir(s, spec));
        int dur = Math.max(80, s.exitDurationMs);
        float w = host.widthPx();
        float h = host.heightPx();

        boolean slide = type == Settings.ANIM_SLIDE || type == Settings.ANIM_FOLLOW_STYLE;
        boolean scale = type == Settings.ANIM_SCALE;

        float stiff = stiffnessFor(dur * 0.55f);
        SpringAnimation alpha = SpringAnimation.of(root, SpringAnimation.PROP_ALPHA, 0f)
                .setStartValue(root.getAlpha())
                .setStiffness(stiff)
                .setDampingRatioNoBouncy();
        player.track(alpha);
        alpha.start();

        if (slide) {
            float tx = dirOffsetX(dir, w) * 1.6f;
            float ty = dirOffsetY(dir, h) * 1.6f;
            if (tx != 0f) {
                SpringAnimation a = SpringAnimation.of(root, SpringAnimation.PROP_TRANSLATION_X, tx)
                        .setStartValue(root.getTranslationX())
                        .setStiffness(stiff)
                        .setDampingRatioNoBouncy();
                player.track(a);
                a.start();
            }
            if (ty != 0f) {
                SpringAnimation a = SpringAnimation.of(root, SpringAnimation.PROP_TRANSLATION_Y, ty)
                        .setStartValue(root.getTranslationY())
                        .setStiffness(stiff)
                        .setDampingRatioNoBouncy();
                player.track(a);
                a.start();
            }
        } else if (scale) {
            for (int prop : new int[]{SpringAnimation.PROP_SCALE_X, SpringAnimation.PROP_SCALE_Y}) {
                SpringAnimation a = SpringAnimation.of(root, prop, 0.8f)
                        .setStartValue(root.getScaleX())
                        .setStiffness(stiff)
                        .setDampingRatioNoBouncy();
                player.track(a);
                a.start();
            }
        }

        if (onEnd != null) {
            player.postDelayed(onEnd, dur + 120);
        }
    }

    // ==================== 回弹（轻滑未过阈值时归位） ====================

    public static void playBounce(Host host, Settings s, Player player, Runnable onEnd) {
        View root = host.root();
        float stiff = stiffnessFor(Math.max(160, s.enterDurationMs));
        SpringAnimation tx = SpringAnimation.of(root, SpringAnimation.PROP_TRANSLATION_X, 0f)
                .setStartValue(root.getTranslationX())
                .setStiffness(stiff)
                .setDampingRatio(0.55f); // 欠阻尼 = 果冻回弹
        player.track(tx);
        tx.start();
        SpringAnimation ty = SpringAnimation.of(root, SpringAnimation.PROP_TRANSLATION_Y, 0f)
                .setStartValue(root.getTranslationY())
                .setStiffness(stiff)
                .setDampingRatio(0.55f);
        player.track(ty);
        ty.start();
        SpringAnimation al = SpringAnimation.of(root, SpringAnimation.PROP_ALPHA, 1f)
                .setStartValue(root.getAlpha())
                .setStiffness(stiff)
                .setDampingRatioNoBouncy();
        player.track(al);
        al.start();
        if (onEnd != null) {
            player.postDelayed(onEnd, 420);
        }
    }

    // ==================== 按压反馈 ====================

    public static void pressDown(View v) {
        v.animate().scaleX(0.97f).scaleY(0.97f)
                .setDuration(80)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
    }

    public static void pressUp(View v) {
        v.animate().scaleX(1f).scaleY(1f)
                .setDuration(150)
                .setInterpolator(new android.view.animation.OvershootInterpolator(0.5f))
                .start();
    }

    // ==================== 工具 ====================

    /** 由目标时长（ms）反推弹簧刚度：settling ≈ 4/(zeta*wn)，zeta 取 0.75。 */
    public static float stiffnessFor(float durationMs) {
        float t = Math.max(0.05f, durationMs / 1000f);
        float wn = 4f / (0.75f * t);
        return wn * wn;
    }

    /**
     * 由“弹性强度”用户设置（0.3 ~ 2.0，1.0 = 默认）反推弹簧阻尼比。
     *
     * <p>强度越大 → 阻尼越小 → 回弹越明显；强度 &lt; 1 → 阻尼趋近 1.0（几乎不回弹）。
     * 旧版 enterOvershoot 只被读取从不使用（UI 滑块无效），此处补上真实消费。</p>
     */
    public static float bounceDamping(float overshoot, float base) {
        float o = Math.max(0.1f, overshoot);
        return Math.max(0.3f, Math.min(1f, base / o));
    }

    public static int resolveEnterDir(Settings s, StyleSpec spec) {
        if (s.enterDir == Settings.DIR_AUTO) {
            return dirFromAnchor(s.anchor);
        }
        return s.enterDir;
    }

    public static int dirFromAnchor(int anchor) {
        switch (anchor) {
            case Settings.ANCHOR_TOP_LEFT:
            case Settings.ANCHOR_MID_LEFT:
            case Settings.ANCHOR_BOTTOM_LEFT:
                return Settings.DIR_LEFT;
            case Settings.ANCHOR_TOP_RIGHT:
            case Settings.ANCHOR_MID_RIGHT:
            case Settings.ANCHOR_BOTTOM_RIGHT:
                return Settings.DIR_RIGHT;
            case Settings.ANCHOR_TOP_CENTER:
                return Settings.DIR_TOP;
            case Settings.ANCHOR_BOTTOM_CENTER:
                return Settings.DIR_BOTTOM;
            default:
                return Settings.DIR_TOP;
        }
    }

    public static int reverseDir(int dir) {
        switch (dir) {
            case Settings.DIR_TOP:
                return Settings.DIR_BOTTOM;
            case Settings.DIR_BOTTOM:
                return Settings.DIR_TOP;
            case Settings.DIR_LEFT:
                return Settings.DIR_RIGHT;
            case Settings.DIR_RIGHT:
                return Settings.DIR_LEFT;
            default:
                return Settings.DIR_TOP;
        }
    }

    private static float dirOffsetX(int dir, float w) {
        switch (dir) {
            case Settings.DIR_LEFT:
                return -w * 1.2f;
            case Settings.DIR_RIGHT:
                return w * 1.2f;
            default:
                return 0f;
        }
    }

    private static float dirOffsetY(int dir, float h) {
        switch (dir) {
            case Settings.DIR_TOP:
                return -h * 1.4f;
            case Settings.DIR_BOTTOM:
                return h * 1.4f;
            default:
                return 0f;
        }
    }

    private static void staggerIn(Player player, View v, long delayMs,
                                  float fromScale, float toScale) {
        if (v == null) return;
        player.postDelayed(() -> {
            // 退场取消与本任务的竞态：视图已离开窗口时不再点亮
            if (v.getParent() == null) return;
            SpringAnimation sx = SpringAnimation.of(v, SpringAnimation.PROP_SCALE_X, toScale)
                    .setStartValue(fromScale)
                    .setStiffness(stiffnessFor(300))
                    .setDampingRatio(0.6f);
            SpringAnimation sy = SpringAnimation.of(v, SpringAnimation.PROP_SCALE_Y, toScale)
                    .setStartValue(fromScale)
                    .setStiffness(stiffnessFor(300))
                    .setDampingRatio(0.6f);
            SpringAnimation al = SpringAnimation.of(v, SpringAnimation.PROP_ALPHA, 1f)
                    .setStartValue(0f)
                    .setStiffness(stiffnessFor(180))
                    .setDampingRatioNoBouncy();
            player.track(sx);
            player.track(sy);
            player.track(al);
            sx.start();
            sy.start();
            al.start();
        }, delayMs);
    }

    private static void staggerFadeIn(Player player, View v, long delayMs, float fromTransY) {
        if (v == null) return;
        player.postDelayed(() -> {
            // 退场取消与本任务的竞态：视图已离开窗口时不再点亮
            if (v.getParent() == null) return;
            SpringAnimation ty = SpringAnimation.of(v, SpringAnimation.PROP_TRANSLATION_Y, 0f)
                    .setStartValue(fromTransY)
                    .setStiffness(stiffnessFor(240))
                    .setDampingRatioNoBouncy();
            SpringAnimation al = SpringAnimation.of(v, SpringAnimation.PROP_ALPHA, 1f)
                    .setStartValue(0f)
                    .setStiffness(stiffnessFor(200))
                    .setDampingRatioNoBouncy();
            player.track(ty);
            player.track(al);
            ty.start();
            al.start();
        }, delayMs);
    }
}
