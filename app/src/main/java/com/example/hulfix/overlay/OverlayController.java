package com.example.hulfix.overlay;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.hulfix.core.CooldownEngine;
import com.example.hulfix.core.MainHandler;
import com.example.hulfix.core.MessageLedger;
import com.example.hulfix.core.PlatformCompat;
import com.example.hulfix.core.SceneDetector;
import com.example.hulfix.core.Settings;
import com.example.hulfix.core.SettingsRepository;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 悬浮窗控制器：HULFix 的中枢。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>对接所有 hook 入口，统一走 {@link #offer(StatusBarNotification)}；</li>
 *   <li>调 {@link CooldownEngine} 做放行/抑制决策，抑制时回报台账与广播；</li>
 *   <li>视图池复用（同一样式/尺寸只建一次视图树，之后仅换内容）——这是响应速度的关键；</li>
 *   <li>状态机 + 生命周期锁，防止并发渲染互相踩踏；</li>
 *   <li>动画 / 手势 / 自动消失 / 倒计时进度环编排；</li>
 *   <li>向 App 进程广播事件（展示/抑制/划掉），供统计与忽略列表持久化。</li>
 * </ul>
 */
public final class OverlayController implements GestureHandler.Callback {

    // ==================== 事件广播契约（与 App 端 ui/StatsReceiver 对应） ====================
    public static final String ACTION_STAT_EVENT = "com.example.hulfix.action.STAT_EVENT";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_PKG = "pkg";
    public static final String EXTRA_KEY = "key";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_REASON = "reason";
    public static final String EVENT_SHOWN = "shown";
    public static final String EVENT_SUPPRESSED = "suppressed";
    public static final String EVENT_DISMISSED = "dismissed";

    /**
     * 浮窗窗口类型。首选 {@link WindowManager.LayoutParams#TYPE_APPLICATION_OVERLAY}
     * （官方应用浮窗类型 2038；本模块运行在 SystemUI / system_server，uid 1000，
     * 应用浮窗权限检查直接放行）。个别 GSI / ROM 上若被 WMS 拒绝，
     * 回退到旧版验证过的 2017（见 {@link #addViewWithFallback}）。
     */
    private static final int WINDOW_TYPE = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    private static final int WINDOW_TYPE_LEGACY = 2017;
    private static final String MEDIA_SESSION_KEY = "android.mediaSession";

    private Context mContext;
    private WindowManager mWindowManager;
    /** 主线程 Handler：惰性创建（模块类在 pre-looper 阶段加载，不能提前 new）。 */
    private final MainHandler mHandler = new MainHandler();

    private final SettingsRepository mSettingsRepo = SettingsRepository.instance();
    private final MessageLedger mLedger = new MessageLedger();
    private final SceneDetector mSceneDetector = new SceneDetector();
    private final CooldownEngine mEngine = new CooldownEngine(mLedger,
            new CooldownEngine.TimeSource() {
                @Override
                public long elapsed() {
                    return SystemClock.elapsedRealtime();
                }

                @Override
                public long wall() {
                    return System.currentTimeMillis();
                }
            });

    // ==================== 视图池 ====================
    private OverlayRenderer.Build mPooledBuild;
    private String mPooledKey;

    // ==================== 当前悬浮窗状态 ====================
    private View mOverlay;
    private String mCurrentKey;
    private String mCurrentContentHash;
    private PendingIntent mCurrentContentIntent;
    /** 当前浮窗对应的通知（设置变化时用于静默重绘）。 */
    private StatusBarNotification mCurrentSbn;
    /** 实际生效的窗口类型（addView 可能降级到 legacy 2017；updateViewLayout 必须用同一类型）。 */
    private int mActiveWindowType = WINDOW_TYPE;
    /** 上次应用到当前窗口的位置（热更新时判断是否需要 updateViewLayout）。 */
    private int mAppliedAnchor = Integer.MIN_VALUE;
    private int mAppliedOffsetX;
    private int mAppliedOffsetY;
    private final AnimPresets.Player mPlayer = new AnimPresets.Player();
    private Runnable mAutoDismissRunnable;
    private Runnable mRingRunnable;
    private long mShowStartElapsed;
    private int mAutoDismissMs;

    // ==================== 生命周期锁 ====================
    private volatile boolean mLifecycleLocked;
    private long mLifecycleLockStart;
    private String mLifecycleLockKey;

    // ==================== 其他 ====================
    private final Map<String, String> mLabelCache = new HashMap<>();
    private final Map<String, Integer> mImportanceCache = new HashMap<>();
    private boolean mScreenReceiverRegistered;
    private BroadcastReceiver mScreenReceiver;
    private volatile boolean mInitialized;

    private enum State { IDLE, ENTERING, SHOWING, EXITING, DRAGGING }

    private State mState = State.IDLE;

    // ==================== 初始化 ====================

    public void init(Context context) {
        if (mInitialized) return;
        mInitialized = true;
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mSceneDetector.init(context);
        mSettingsRepo.start(context);
        // 设置热更新：App 改样式 / 尺寸后，正在显示的浮窗立即按新设置重绘，
        // 不必等系统重启，也不必等"下一条通知"。
        mSettingsRepo.addListener(this::onSettingsReloaded);
        registerScreenReceiver(context);
        log("controller initialized");
    }

    /**
     * 设置重新加载后的回调（App 端广播 / 文件变化触发）。
     *
     * <p>两类变化分别处理：</p>
     * <ul>
     *   <li><b>位置</b>（锚点 / XY 偏移）：窗口参数只在 addView 时应用，因此这里必须显式
     *       {@code updateViewLayout} 才能让<b>正在显示</b>的浮窗立即移动；未显示时下一
     *       条通知自然会用上新位置（都不需要重启系统）。</li>
     *   <li><b>样式 / 几何</b>：走静默重绘（重建或复用视图树后淡入换卡）。</li>
     * </ul>
     */
    private void onSettingsReloaded(Settings s) {
        if (!mInitialized) return;
        mHandler.post(() -> {
            try {
                if (mOverlay == null || mOverlay.getParent() == null) return;
                // 总开关关闭：正在显示的浮窗立即消失（否则要等到自动消失计时走完）
                if (!s.enabled) {
                    log("module disabled while showing, dismissing");
                    removeOverlaySync();
                    return;
                }
                StyleSpec spec = StyleRegistry.byId(s.styleId, s.customStylesJson);
                boolean positionChanged = s.anchor != mAppliedAnchor
                        || s.offsetXDp != mAppliedOffsetX || s.offsetYDp != mAppliedOffsetY;
                if (!poolKeyFor(s, spec, isDark(s)).equals(mPooledKey)) {
                    log("settings changed while showing, restyling");
                    restyleNow(s);
                    return;
                }
                if (positionChanged && mPooledBuild != null) {
                    log("position changed while showing, moving window");
                    WindowManager.LayoutParams params =
                            buildLayoutParams(s, spec, mPooledBuild, mActiveWindowType);
                    mWindowManager.updateViewLayout(mOverlay, params);
                    mAppliedAnchor = s.anchor;
                    mAppliedOffsetX = s.offsetXDp;
                    mAppliedOffsetY = s.offsetYDp;
                }
            } catch (Throwable t) {
                log("settings-reload error: " + t);
            }
        });
    }

    public SceneDetector sceneDetector() {
        return mSceneDetector;
    }

    private void registerScreenReceiver(Context context) {
        if (mScreenReceiverRegistered) return;
        try {
            mScreenReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                        removeOverlayImmediate();
                    }
                }
            };
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
            // 系统广播（SCREEN_OFF）：NOT_EXPORTED 即可收到，且满足 Android 14 的 flag 强制要求
            PlatformCompat.registerReceiver(context, mScreenReceiver, filter,
                    mHandler.get(), false);
            mScreenReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }

    // ==================== 入口：所有 hook 点汇流到这里 ====================

    public void offer(StatusBarNotification sbn) {
        if (sbn == null) return;
        Settings s = mSettingsRepo.get();
        if (!s.enabled) return;

        CooldownEngine.SbnInfo info = buildSbnInfo(s, sbn);

        CooldownEngine.SceneState scene = mSceneDetector.snapshot(s);
        CooldownEngine.Decision decision = mEngine.evaluate(s, info, scene);
        if (decision.isShow()) {
            showHeadsUp(s, sbn, info);
        } else {
            mEngine.onSuppressed(s, info, decision.reason);
            broadcast(EVENT_SUPPRESSED, info, decision.reason);
        }
    }

    /**
     * 通知被移除（用户从通知栏划掉 / 应用主动 cancel）：若当前悬浮窗正是该通知，
     * 同步退场消失——与原生 heads-up 行为一致，避免"通知已不在、浮窗还在飘"。
     * 复用已有的 {@link #dismissAnimated(int)}（不新增解除逻辑）：此路径不记入用户
     * 忽略列表（不是对悬浮窗本身的手势操作）。
     */
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        final String key = sbn.getKey();
        if (key == null || mCurrentKey == null || !key.equals(mCurrentKey)) return;
        if (mOverlay == null || mOverlay.getParent() == null) return;
        log("current notification removed externally, dismissing overlay");
        dismissAnimated(-1);
    }

    // ==================== 展示流程（主线程） ====================

    private void showHeadsUp(Settings s, StatusBarNotification sbn, CooldownEngine.SbnInfo info) {
        showHeadsUp(s, sbn, info, false);
    }

    /**
     * @param restyle true = 设置变化后的静默重绘：跳过生命周期锁、统计与事件广播，
     *                只用淡入过渡换上新卡片（不重播从屏幕外飞入的入场动画）。
     */
    private void showHeadsUp(Settings s, StatusBarNotification sbn, CooldownEngine.SbnInfo info,
                             boolean restyle) {
        if (mContext == null || mWindowManager == null) return;
        mHandler.post(() -> {
            try {
                if (!restyle && isLifecycleLocked()) return;

                final String key = sbn.getKey();
                final Notification notification = sbn.getNotification();
                final PendingIntent contentIntent = notification.contentIntent;

                // overlay 正在显示时的同 key 处理
                if (key != null && key.equals(mCurrentKey) && mOverlay != null
                        && mOverlay.getParent() != null) {
                    if (info.contentHash.equals(mCurrentContentHash)) {
                        return; // 同内容更新 → 忽略
                    }
                    // 内容变化 → 同步摘除旧窗口后重来。
                    // 必须同步：异步移除会晚于下面的 addView 执行，把新窗口误删
                    // （旧版"同 key 更新时卡片闪没"的根因）；同步移除同时会抬升
                    // 移除代际，作废任何在途的异步移除。
                    removeOverlaySync();
                }

                boolean dark = isDark(s);
                StyleSpec spec = StyleRegistry.byId(s.styleId, s.customStylesJson);

                // 内容提取
                NotificationContent nc = extractContent(sbn, notification.extras);
                String appName = resolveAppLabel(sbn.getPackageName());

                // 视图池
                OverlayRenderer.Build build = obtainBuild(s, spec, dark);
                rebindBuild(build, spec, appName, nc.title, nc.content,
                        notification.getSmallIcon(), notification.color, s);
                // 未开内容错落时，子视图直接可见（resetChild 默认置 0 等待 stagger 点亮）
                if (!s.staggerContent) {
                    setChildrenAlpha(build, 1f);
                }

                // 窗口参数
                WindowManager.LayoutParams params = buildLayoutParams(s, spec, build, WINDOW_TYPE);
                build.root.setAlpha(0f);
                if (!addViewWithFallback(s, spec, build, params)) {
                    return;
                }

                // 有界背景模糊（磨砂玻璃）：必须在 addView 之后——此刻根视图的
                // mParent 才是本窗口的 ViewRootImpl。
                applyWindowBlur(build, spec, s);

                mOverlay = build.root;
                mCurrentKey = key;
                mCurrentContentHash = info.contentHash;
                mCurrentContentIntent = contentIntent;
                mCurrentSbn = sbn;
                mAppliedAnchor = s.anchor;
                mAppliedOffsetX = s.offsetXDp;
                mAppliedOffsetY = s.offsetYDp;
                mState = State.ENTERING;

                // 手势接线（内容层可触摸）
                View touchTarget = build.contentLayer != null ? build.contentLayer : build.root;
                GestureHandler gh = new GestureHandler(s, OverlayController.this);
                touchTarget.setOnTouchListener(gh);

                // 事件回报（静默重绘不计入统计，也不重复上报"展示"）
                if (!restyle) {
                    mEngine.onShown(s, info);
                    broadcast(EVENT_SHOWN, info, 0);
                }

                // 动画 + 生命周期
                mShowStartElapsed = SystemClock.elapsedRealtime();
                mAutoDismissMs = s.autoDismissMs;
                mPlayer.cancelAll();
                if (restyle) {
                    // 静默重绘：内容直接可见 + 140ms 淡入，避免卡片"闪一下"
                    setChildrenAlpha(build, 1f);
                    build.root.animate().cancel();
                    build.root.setAlpha(0f);
                    build.root.animate().alpha(1f).setDuration(140).start();
                    mState = State.SHOWING;
                    lockLifecycle(key);
                } else {
                    AnimPresets.Host host = hostOf(build);
                    lockLifecycle(key);
                    AnimPresets.playEnter(host, spec, s, mPlayer, () -> {
                        if (mState == State.ENTERING) mState = State.SHOWING;
                    });
                }
                startRingProgress(build, s, spec);
                scheduleAutoDismiss(s);
                log("shown: " + nc.title);
            } catch (Throwable t) {
                log("show error: " + t);
            }
        });
    }

    // ==================== 视图池 ====================

    /**
     * 视图池复用键。
     *
     * <p><b>必须包含所有影响卡片外观的字段</b>：旧版只放了 id / 宽度 / 两个缩放，
     * 于是"编辑自定义样式（圆角、内边距、字号、最小高度…）"后复用键不变 → 一直复用旧
     * 视图树，看起来像"改了样式要重启才生效"。现在把几何参数一并纳入，并用
     * {@link StyleSpec#contentHash()} 兜住配色等一切视觉参数（重新导入只改颜色的
     * 自定义样式也能正确重建）。</p>
     */
    private String poolKeyFor(Settings s, StyleSpec spec, boolean dark) {
        return spec.id + "|" + dark + "|" + expectedWidthPx(s)
                + "|" + Math.round(s.bgAlpha * 100f)
                + "|" + Math.round(s.cornerScale * 100f)
                + "|lens=" + (OverlayRenderer.lensActive(spec, s) ? 1 : 0)
                + "|wb=" + (OverlayRenderer.windowBlurActive(mContext, spec, s) ? 1 : 0)
                + "|spec=" + spec.contentHash()
                + "|r=" + Math.round(spec.cornerRadiusDp * 10f)
                + "|ph=" + Math.round(spec.paddingHDp * 10f)
                + "|pv=" + Math.round(spec.paddingVDp * 10f)
                + "|icon=" + Math.round(spec.iconSizeDp * 10f)
                + "|t1=" + Math.round(spec.titleSizeSp * 10f)
                + "|t2=" + Math.round(spec.textSizeSp * 10f)
                + "|t3=" + Math.round(spec.appNameSizeSp * 10f)
                + "|minH=" + Math.round(spec.minHeightDp * 10f)
                + "|sw=" + Math.round(spec.strokeWidthDp * 10f)
                + "|opt=" + (spec.showIcon ? 1 : 0) + (spec.showAppName && s.showAppName ? 1 : 0)
                + (spec.progressRing ? 1 : 0) + (spec.pulseDot ? 1 : 0)
                + "|lines=" + s.maxTextLines;
    }

    /** 按当前设置静默重绘正在显示的浮窗（样式 / 尺寸变化时）。 */
    private void restyleNow(Settings s) {
        final StatusBarNotification sbn = mCurrentSbn;
        if (sbn == null) {
            removeOverlaySync();
            return;
        }
        final CooldownEngine.SbnInfo info = buildSbnInfo(s, sbn);
        removeOverlaySync();
        showHeadsUp(s, sbn, info, true);
    }

    private void setChildrenAlpha(OverlayRenderer.Build b, float alpha) {
        if (b.iconView != null) b.iconView.setAlpha(alpha);
        if (b.appNameView != null) b.appNameView.setAlpha(alpha);
        if (b.titleView != null) b.titleView.setAlpha(alpha);
        if (b.textView != null) b.textView.setAlpha(alpha);
    }

    private OverlayRenderer.Build obtainBuild(Settings s, StyleSpec spec, boolean dark) {
        String poolKey = poolKeyFor(s, spec, dark);
        if (mPooledBuild != null && poolKey.equals(mPooledKey)) {
            return mPooledBuild;
        }
        // 丢弃旧池
        if (mPooledBuild != null) {
            try {
                if (mPooledBuild.root.getParent() != null) {
                    mWindowManager.removeViewImmediate(mPooledBuild.root);
                }
            } catch (Throwable ignored) {
            }
            mPooledBuild = null;
        }
        mPooledBuild = OverlayRenderer.build(mContext, spec, s, dark,
                "", "", "", null, 0);
        mPooledKey = poolKey;
        return mPooledBuild;
    }

    /** 复用视图树：只换内容、重置变换与 stagger 初态。 */
    private void rebindBuild(OverlayRenderer.Build b, StyleSpec spec,
                             CharSequence appName, CharSequence title, CharSequence text,
                             android.graphics.drawable.Icon icon, int accentOverride,
                             Settings s) {
        View root = b.root;
        root.animate().cancel();
        root.setTranslationX(0f);
        root.setTranslationY(0f);
        root.setScaleX(1f);
        root.setScaleY(1f);
        root.setAlpha(1f);

        resetChild(b.iconView);
        resetChild(b.appNameView);
        resetChild(b.titleView);
        resetChild(b.textView);

        if (b.iconView != null) {
            if (icon != null) b.iconView.setImageIcon(icon);
            boolean iconVisible = spec.showIcon;
            b.iconView.setVisibility(iconVisible ? View.VISIBLE : View.GONE);
            // 图标隐藏时整个容器一起隐藏，避免左侧残留固定占位
            if (b.iconFrame != null) {
                b.iconFrame.setVisibility(iconVisible ? View.VISIBLE : View.GONE);
            }
        }
        if (b.appNameView != null) {
            b.appNameView.setText(appName);
            b.appNameView.setVisibility(
                    (spec.showAppName && s.showAppName) ? View.VISIBLE : View.GONE);
        }
        if (b.titleView != null) b.titleView.setText(title);
        if (b.textView != null) {
            b.textView.setText(text);
            b.textView.setVisibility(android.text.TextUtils.isEmpty(text)
                    ? View.GONE : View.VISIBLE);
            b.textView.setMaxLines(Math.max(1, s.maxTextLines));
        }
        if (b.ring != null) b.ring.setProgress(1f);

        // ---------- 真折射玻璃层 ----------
        if (b.lensView != null) {
            // 玻璃后方的 App 大图标（每次通知独立加载 Drawable 实例，
            // 避免与内容层 ImageView 共享 bounds 互相干扰）
            if (icon != null) {
                try {
                    b.lensView.setBackdropIcon(icon.loadDrawable(mContext));
                } catch (Throwable ignored) {
                }
            }
            // 滑块可能在两次通知之间被修改：重算 uniform，保证下次弹出即最新参数
            if (b.lensUniforms != null) {
                b.lensUniforms = OverlayRenderer.lensUniformsFor(mContext, spec, s,
                        b.cornerRadiusPx, b.lensUniforms.highlightArgb());
                b.lensView.setLensUniforms(b.lensUniforms);
            }
        }
    }

    private void resetChild(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setAlpha(0f); // stagger 入场时再亮起
    }

    private int expectedWidthPx(Settings s) {
        float density = mContext.getResources().getDisplayMetrics().density;
        float sw = mContext.getResources().getDisplayMetrics().widthPixels;
        return (int) (Math.min(sw * 0.40f, 340f * density)
                * Math.max(0.4f, Math.min(1.6f, s.widthScale)));
    }

    private WindowManager.LayoutParams buildLayoutParams(Settings s, StyleSpec spec,
                                                         OverlayRenderer.Build b, int windowType) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                b.widthPx, WindowManager.LayoutParams.WRAP_CONTENT, windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        int gravity = Gravity.TOP | Gravity.LEFT;
        switch (s.anchor) {
            case Settings.ANCHOR_TOP_LEFT:
                gravity = Gravity.TOP | Gravity.LEFT;
                break;
            case Settings.ANCHOR_TOP_CENTER:
                gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                break;
            case Settings.ANCHOR_TOP_RIGHT:
                gravity = Gravity.TOP | Gravity.RIGHT;
                break;
            case Settings.ANCHOR_MID_LEFT:
                gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
                break;
            case Settings.ANCHOR_CENTER:
                gravity = Gravity.CENTER;
                break;
            case Settings.ANCHOR_MID_RIGHT:
                gravity = Gravity.CENTER_VERTICAL | Gravity.RIGHT;
                break;
            case Settings.ANCHOR_BOTTOM_LEFT:
                gravity = Gravity.BOTTOM | Gravity.LEFT;
                break;
            case Settings.ANCHOR_BOTTOM_CENTER:
                gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                break;
            case Settings.ANCHOR_BOTTOM_RIGHT:
                gravity = Gravity.BOTTOM | Gravity.RIGHT;
                break;
            default:
                break;
        }
        params.gravity = gravity;
        params.x = dp(s.offsetXDp);
        params.y = dp(s.offsetYDp);

        // 磨砂玻璃已改为"有界背景模糊"（见 applyWindowBlur）：模糊区域 = 卡片本身。
        // 旧实现用 FLAG_BLUR_BEHIND + setBlurBehindRadius，那会在 WMS 里经 Dimmer
        // 创建一张覆盖整个 DisplayArea 的全屏模糊层——即"卡片下面盖了一层全屏模糊"。
        return params;
    }

    /**
     * 有界背景模糊（磨砂玻璃）：把 {@code BackgroundBlurDrawable} 挂到卡片根背景。
     *
     * <p>机制（与系统对话框的磨砂背景完全一致）：根视图的 {@code getParent()} 即本窗口的
     * {@code ViewRootImpl}（addView 后由框架 {@code assignParent}）；经它的
     * {@code createBackgroundBlurDrawable()} 拿到绑定本窗口 Aggregator 的 Drawable，
     * 设置半径 / 圆角 / 罩色后挂为根背景。Drawable 边界即模糊区域，由 Aggregator 汇总
     * 成 {@code setBlurRegions} 送到<b>本窗口 surface</b>——卡片以外没有任何模糊。</p>
     *
     * <p>时序与清理：</p>
     * <ul>
     *   <li>必须在 addView <b>之后</b>调用（拿得到 ViewRootImpl）；</li>
     *   <li>先清掉根背景再挂——视图池复用时 root 可能带着上一轮（旧 ViewRootImpl）
     *       的模糊 Drawable，不摘掉会残留失效模糊区域；</li>
     *   <li>渲染阶段已按"模糊会透出"预降背景层透明度，因此任何导致模糊没挂上的
     *       路径都必须 {@code restoreBgAlpha()} 恢复，否则卡片半透明地浮在桌面上；</li>
     *   <li>反之，挂载成功则必须 {@code applyBlurDiscount()} 把折扣补回来——卡片可能
     *       来自视图池，上次退场时 {@code detachWindowBlur} 刚恢复过透明度。</li>
     * </ul>
     */
    private void applyWindowBlur(OverlayRenderer.Build b, StyleSpec spec, Settings s) {
        // 无论激活与否，先摘掉可能残留的旧模糊背景（池化 root 跨轮次复用）
        try {
            b.root.setBackground(null);
        } catch (Throwable ignored) {
        }
        if (!OverlayRenderer.windowBlurActive(mContext, spec, s)) {
            // 未激活（样式无半径 / API<31 / 系统禁用了跨窗口模糊）：
            // 恢复"无模糊"设计透明度——池内视图可能仍带着上一轮的折扣。
            b.restoreBgAlpha();
            return;
        }
        try {
            Object viewRoot = b.root.getParent();
            if (viewRoot == null) {
                b.restoreBgAlpha();
                return;
            }
            Object drawable = XposedHelpers.callMethod(viewRoot, "createBackgroundBlurDrawable");
            if (!(drawable instanceof android.graphics.drawable.Drawable)) {
                b.restoreBgAlpha();
                return;
            }
            float density = mContext.getResources().getDisplayMetrics().density;
            // 半径钳到系统上限 150px（WindowManager.MAX_BLUR_BEHIND_RADIUS 为 @hide，
            // 值为 150）：样式允许 60dp，超高密度屏上折算可超 200px，
            // BackgroundBlurDrawable 会直接抛异常
            int radiusPx = Math.min(150,
                    (int) (spec.blurRadiusDp * density + 0.5f));
            XposedHelpers.callMethod(drawable, "setBlurRadius", radiusPx);
            XposedHelpers.callMethod(drawable, "setCornerRadius", b.cornerRadiusPx);
            // 罩色：卡片底色稀释后作为霜面上的薄 tint（过厚会完全遮住霜感）
            StyleSpec.Palette pal = isDark(s) ? spec.dark : spec.light;
            int tint = scaleAlpha(pal.bgColors[0], clampAlpha(s.bgAlpha) * 0.30f);
            XposedHelpers.callMethod(drawable, "setColor", tint);
            b.root.setBackground((android.graphics.drawable.Drawable) drawable);
            // 补回霜面折扣（池化卡片上次退场时被 restoreBgAlpha 恢复过）
            b.applyBlurDiscount();
        } catch (Throwable t) {
            log("window blur unavailable: " + t);
            b.restoreBgAlpha();
        }
    }

    private static float clampAlpha(float v) {
        return Math.max(0.15f, Math.min(1f, v));
    }

    private static int scaleAlpha(int argb, float f) {
        int a = (argb >>> 24) & 0xFF;
        return (Math.max(0, Math.min(255, (int) (a * f))) & 0xFF) << 24 | (argb & 0x00FFFFFF);
    }

    /**
     * 把视图添加到窗口，失败时带上日志（旧版这里静默 {@code return}，addView 被拒时
     * 毫无线索，是"模块没反应"类问题最难排查的一类）。
     *
     * <p>两级兜底：</p>
     * <ol>
     *   <li>{@link IllegalStateException}（视图还挂在旧父窗口上）→ 同步摘除后重试；</li>
     *   <li>其他异常（个别 GSI/ROM 拒绝 TYPE_APPLICATION_OVERLAY）→ 换旧版 2017 再试一次。</li>
     * </ol>
     */
    private boolean addViewWithFallback(Settings s, StyleSpec spec,
                                        OverlayRenderer.Build b,
                                        WindowManager.LayoutParams params) {
        try {
            mWindowManager.addView(b.root, params);
            mActiveWindowType = params.type;
            return true;
        } catch (IllegalStateException e) {
            // 视图仍被上一次的窗口持有：同步摘下再重试
            try {
                mWindowManager.removeViewImmediate(b.root);
            } catch (Throwable ignored) {
            }
            try {
                mWindowManager.addView(b.root, params);
                mActiveWindowType = params.type;
                return true;
            } catch (Throwable t) {
                log("addView retry failed: " + t);
                return false;
            }
        } catch (Throwable t) {
            if (params.type == WINDOW_TYPE && WINDOW_TYPE != WINDOW_TYPE_LEGACY) {
                log("addView(TYPE_APPLICATION_OVERLAY) rejected: " + t
                        + ", retrying with legacy type " + WINDOW_TYPE_LEGACY);
                try {
                    mWindowManager.removeViewImmediate(b.root);
                } catch (Throwable ignored) {
                }
                WindowManager.LayoutParams legacy =
                        buildLayoutParams(s, spec, b, WINDOW_TYPE_LEGACY);
                try {
                    mWindowManager.addView(b.root, legacy);
                    mActiveWindowType = WINDOW_TYPE_LEGACY;
                    return true;
                } catch (Throwable t2) {
                    log("addView(legacy) failed: " + t2);
                    return false;
                }
            }
            log("addView failed: " + t);
            return false;
        }
    }

    private AnimPresets.Host hostOf(OverlayRenderer.Build b) {
        return new AnimPresets.Host() {
            @Override
            public View root() {
                return b.root;
            }

            @Override
            public View icon() {
                return b.iconView;
            }

            @Override
            public View appName() {
                return b.appNameView;
            }

            @Override
            public View title() {
                return b.titleView;
            }

            @Override
            public View text() {
                return b.textView;
            }

            @Override
            public View pulse() {
                return b.pulseDot;
            }

            @Override
            public float widthPx() {
                return b.widthPx;
            }

            @Override
            public float heightPx() {
                return b.estimatedHeightPx;
            }
        };
    }

    // ==================== 自动消失 + 进度环 ====================

    private void scheduleAutoDismiss(Settings s) {
        cancelAutoDismiss();
        mAutoDismissRunnable = () -> dismissAnimated(-1);
        mHandler.postDelayed(mAutoDismissRunnable, Math.max(1000, s.autoDismissMs));
    }

    private void startRingProgress(OverlayRenderer.Build b, Settings s, StyleSpec spec) {
        cancelRing();
        if (b.ring == null) return;
        final long total = Math.max(1000, s.autoDismissMs);
        mRingRunnable = new Runnable() {
            @Override
            public void run() {
                if (mOverlay == null || b.ring == null) return;
                long elapsed = SystemClock.elapsedRealtime() - mShowStartElapsed;
                float remain = 1f - Math.min(1f, elapsed / (float) total);
                b.ring.setProgress(remain);
                if (remain > 0f) {
                    mHandler.postDelayed(this, 60);
                }
            }
        };
        mHandler.post(mRingRunnable);
    }

    private void cancelAutoDismiss() {
        if (mAutoDismissRunnable != null) {
            mHandler.removeCallbacks(mAutoDismissRunnable);
            mAutoDismissRunnable = null;
        }
    }

    private void cancelRing() {
        if (mRingRunnable != null) {
            mHandler.removeCallbacks(mRingRunnable);
            mRingRunnable = null;
        }
    }

    // ==================== 退场 / 移除 ====================

    private void dismissAnimated(int direction) {
        mHandler.post(() -> {
            cancelAutoDismiss();
            if (mOverlay == null || mOverlay.getParent() == null) {
                removeOverlayImmediate();
                return;
            }
            mState = State.EXITING;
            Settings s = mSettingsRepo.get();
            StyleSpec spec = StyleRegistry.byId(s.styleId, s.customStylesJson);
            // 退场开始即摘除有界背景模糊（见 detachWindowBlur 注释）：
            // 模糊区域由 SurfaceFlinger/Aggregator 合成，不随卡片 alpha 动画淡出，
            // 若等动画结束才 removeView，卡片飞出后霜面矩形还会停留一整个退场时长。
            detachWindowBlur();
            AnimPresets.playExit(hostOf(mPooledBuild), spec, s, mPlayer, direction,
                    this::removeOverlayImmediate);
        });
    }

    /**
     * 摘除当前浮窗根视图上的有界背景模糊，并恢复背景层设计透明度。
     *
     * <p><b>为什么退场第一帧就要摘</b>：{@code BackgroundBlurDrawable} 的模糊区域经
     * 窗口 Aggregator 汇总成 {@code setBlurRegions} 交给 SurfaceFlinger 合成，
     * 与视图自身的 alpha / 位移动画<b>完全独立</b>——卡片淡出/飞出时，霜面矩形
     * 仍以全不透明度停留在原位置，直到 {@code removeView} 才消失。这就是用户反馈的
     * "收回后模糊层短暂停留一小会儿才销毁"。</p>
     *
     * <p>摘除后卡片以"无模糊"设计透明度飞出（渲染阶段按模糊会透出预降过背景 alpha，
     * 这里负责恢复），观感上就是一张普通卡片正常退场。</p>
     */
    private void detachWindowBlur() {
        OverlayRenderer.Build b = mPooledBuild;
        if (b == null || b.root == null) return;
        if (mOverlay != null && mOverlay != b.root) return; // 防御：非当前窗口不动
        try {
            b.root.setBackground(null);
        } catch (Throwable ignored) {
        }
        b.restoreBgAlpha();
    }

    private void removeOverlayImmediate() {
        unlockLifecycle("removed");
        mPlayer.cancelAll();
        cancelAutoDismiss();
        cancelRing();

        final View toRemove;
        synchronized (this) {
            toRemove = mOverlay;
            mOverlay = null;
            mCurrentKey = null;
            mCurrentContentHash = null;
            mCurrentContentIntent = null;
            mCurrentSbn = null;
            mState = State.IDLE;
            mAppliedAnchor = Integer.MIN_VALUE;
        }
        if (toRemove != null) {
            try {
                toRemove.setAlpha(0f);
            } catch (Throwable ignored) {
            }
            final WindowManager wm = mWindowManager;
            if (wm != null) {
                // 代际守卫：若此后又发生"重挂窗口 / 新移除"，本次晚到的 removeView 作废，
                // 防止误删刚挂上的新窗口。
                final long gen = ++mRemoveGeneration;
                // 优先同步移除：调用方（退场动画结束回调 / 主线程消息）通常不在视图
                // 遍历期，同步摘窗可省掉一次 post 带来的一帧延迟——"卡片已不可见、
                // 窗口还停留一帧"的残留观感主要靠它消除。遍历期同步移除会抛异常，
                // 此时回退异步（仍受代际守卫保护）。
                if (!removeViewNow(wm, toRemove)) {
                    mHandler.post(() -> {
                        if (gen != mRemoveGeneration) return;
                        if (!removeViewNow(wm, toRemove)) {
                            try {
                                if (toRemove.getParent() != null) wm.removeView(toRemove);
                            } catch (Throwable t) {
                                try {
                                    wm.removeViewImmediate(toRemove);
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    });
                }
            }
        }
    }

    /**
     * 尝试同步移除窗口（已摘除 / 成功移除均返回 true；遍历期抛异常返回 false 由调用方转异步）。
     */
    private boolean removeViewNow(WindowManager wm, View v) {
        try {
            if (v.getParent() != null) {
                wm.removeViewImmediate(v);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 同步移除当前浮窗（同 key 内容变化 / 重绘前调用）。
     *
     * <p>与 {@link #removeOverlayImmediate()} 的区别：立即生效，且抬升移除代际作废
     * 任何在途的异步移除——因此调用方可以在同一流程里紧接着 addView 新窗口，
     * 不必担心晚到的 removeView 把新窗口删掉。</p>
     */
    private void removeOverlaySync() {
        ++mRemoveGeneration;
        unlockLifecycle("removed-sync");
        mPlayer.cancelAll();
        cancelAutoDismiss();
        cancelRing();

        final View toRemove;
        synchronized (this) {
            toRemove = mOverlay;
            mOverlay = null;
            mCurrentKey = null;
            mCurrentContentHash = null;
            mCurrentContentIntent = null;
            mCurrentSbn = null;
            mState = State.IDLE;
            mAppliedAnchor = Integer.MIN_VALUE;
        }
        if (toRemove != null) {
            try {
                toRemove.setAlpha(0f);
            } catch (Throwable ignored) {
            }
            final WindowManager wm = mWindowManager;
            if (wm != null) {
                try {
                    if (toRemove.getParent() != null) wm.removeViewImmediate(toRemove);
                } catch (Throwable ignored) {
                    // 遍历期间同步移除会抛 → 退回异步（有代际守卫，安全）
                    try {
                        if (toRemove.getParent() != null) wm.removeView(toRemove);
                    } catch (Throwable ignored2) {
                    }
                }
            }
        }
    }

    // ==================== 生命周期锁 / 移除代际 ====================
    /**
     * 移除代际：每次发起移除 +1。异步 removeView 执行前比对代际，已被更新的移除
     * （重挂窗口 / 新移除）作废——否则"同 key 内容更新"时晚到的 removeView 会把
     * 刚重新挂上的窗口误删（卡片闪没）。
     */
    private long mRemoveGeneration;

    private void lockLifecycle(String key) {
        mLifecycleLocked = true;
        mLifecycleLockStart = SystemClock.elapsedRealtime();
        mLifecycleLockKey = key;
    }

    private void unlockLifecycle(String reason) {
        if (mLifecycleLocked) {
            log("lifecycle unlocked (" + reason + "), key=" + mLifecycleLockKey);
        }
        mLifecycleLocked = false;
        mLifecycleLockKey = null;
        mLifecycleLockStart = 0;
    }

    private boolean isLifecycleLocked() {
        if (!mLifecycleLocked) return false;
        Settings s = mSettingsRepo.get();
        long maxMs = Math.max(3000, s.autoDismissMs) + 8000L;
        if (SystemClock.elapsedRealtime() - mLifecycleLockStart > maxMs) {
            unlockLifecycle("safety timeout");
            return false;
        }
        View overlay = mOverlay;
        if (overlay == null || overlay.getParent() == null) {
            unlockLifecycle("overlay gone");
            return false;
        }
        return true;
    }

    // ==================== 手势回调 ====================

    @Override
    public boolean isInteractive() {
        return mState == State.SHOWING || mState == State.DRAGGING;
    }

    @Override
    public void onTap() {
        Settings s = mSettingsRepo.get();
        PendingIntent intent = mCurrentContentIntent;
        // “点击打开”开关（hulfix_gesture_tap）：关闭时点击仅收起浮窗，不跳转通知
        if (s.gestureTap && intent != null) performContentClick(intent);
        dismissAnimated(1);
    }

    @Override
    public void onSwipeDismiss(int direction) {
        Settings s = mSettingsRepo.get();
        CooldownEngine.SbnInfo info = currentSbnInfo(s);
        if (info != null) {
            mEngine.onUserDismissed(s, info);
            broadcast(EVENT_DISMISSED, info, 0);
        }
        dismissAnimated(direction);
    }

    @Override
    public void onPullDownExpand() {
        expandStatusBar();
        removeOverlayImmediate();
    }

    @Override
    public void onLongPressIgnore() {
        onSwipeDismiss(1);
    }

    @Override
    public void onDragStart() {
        mState = State.DRAGGING;
        mPlayer.cancelAll();
    }

    @Override
    public void onDrag(float dx, float dy, boolean h, boolean v) {
        if (mOverlay == null) return;
        if (h) {
            mOverlay.setTranslationX(dx);
            mOverlay.setTranslationY(0f);
        } else if (v) {
            mOverlay.setTranslationX(0f);
            mOverlay.setTranslationY(dy);
        } else {
            mOverlay.setTranslationX(dx);
            mOverlay.setTranslationY(dy);
        }
    }

    @Override
    public void onDragAlpha(float alpha) {
        if (mOverlay != null) mOverlay.setAlpha(alpha);
    }

    @Override
    public void onDragEnd(boolean hasMoved) {
        if (mOverlay == null) return;
        mState = State.SHOWING;
        Settings s = mSettingsRepo.get();
        StyleSpec spec = StyleRegistry.byId(s.styleId, s.customStylesJson);
        AnimPresets.playBounce(hostOf(mPooledBuild), s, mPlayer, null);
    }

    @Override
    public void onPressFeedback(boolean down) {
        if (mOverlay == null) return;
        if (down) AnimPresets.pressDown(mOverlay);
        else AnimPresets.pressUp(mOverlay);
    }

    // ==================== 工具 ====================

    private CooldownEngine.SbnInfo currentSbnInfo(Settings s) {
        if (mCurrentKey == null) return null;
        // key 形如 "0|pkg|id|tag|uid"
        CooldownEngine.SbnInfo info = new CooldownEngine.SbnInfo();
        info.key = mCurrentKey;
        String[] parts = mCurrentKey.split("\\|");
        info.pkg = parts.length >= 2 ? parts[1] : mCurrentKey;
        info.title = "";
        return info;
    }

    private void performContentClick(PendingIntent contentIntent) {
        if (contentIntent == null) return;
        try {
            Bundle opts = createLaunchOptions();
            if (opts != null) {
                XposedHelpers.callMethod(contentIntent, "send", mContext, 0, null, null, null, null, opts);
            } else {
                contentIntent.send(mContext, 0, null);
            }
        } catch (Throwable e) {
            try {
                contentIntent.send(mContext, 0, null);
            } catch (Throwable ignored) {
            }
        }
    }

    private Bundle createLaunchOptions() {
        try {
            Class<?> aoClass = Class.forName("android.app.ActivityOptions", true,
                    mContext.getClassLoader());
            Object ao = XposedHelpers.callStaticMethod(aoClass, "makeBasic");
            XposedHelpers.callMethod(ao, "setLaunchWindowingMode", 1);
            return (Bundle) XposedHelpers.callMethod(ao, "toBundle");
        } catch (Throwable t) {
            return null;
        }
    }

    private void expandStatusBar() {
        try {
            Object sbm = mContext.getSystemService("statusbar");
            if (sbm != null) {
                java.lang.reflect.Method expand = sbm.getClass().getMethod("expandNotificationsPanel");
                expand.invoke(sbm);
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isDark(Settings s) {
        if (s.themeMode == Settings.THEME_LIGHT) return false;
        if (s.themeMode == Settings.THEME_DARK) return true;
        try {
            int uiMode = mContext.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return false;
        }
    }

    private int dp(int v) {
        return (int) (v * mContext.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ==================== 通知内容解析 ====================

    private static final class NotificationContent {
        String title = "";
        String content = "";
    }

    private NotificationContent extractContent(StatusBarNotification sbn, Bundle extras) {
        NotificationContent nc = new NotificationContent();
        if (extras == null) {
            nc.title = sbn.getPackageName();
            nc.content = "新消息";
            return nc;
        }
        // 1. InboxStyle / Summary
        CharSequence[] textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (textLines != null && textLines.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < textLines.length && i < 3; i++) {
                if (i > 0) sb.append(" · ");
                sb.append(textLines[i]);
            }
            nc.content = sb.toString();
            if (TextEmpty(extras.getString(Notification.EXTRA_TITLE, ""))) {
                CharSequence summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT);
                if (summary != null && summary.length() > 0) nc.title = summary.toString();
            } else {
                nc.title = extras.getString(Notification.EXTRA_TITLE, "");
            }
            if (TextEmpty(nc.title)) nc.title = resolveAppLabel(sbn.getPackageName());
            return nc;
        }
        // 2. MessagingStyle
        try {
            Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
            if (messages != null && messages.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < messages.length && i < 2; i++) {
                    Object msg = messages[i];
                    if (msg != null) {
                        CharSequence text = (CharSequence) XposedHelpers.callMethod(msg, "getText");
                        if (text != null && text.length() > 0) {
                            if (i > 0) sb.append(" · ");
                            sb.append(text);
                        }
                    }
                }
                nc.content = sb.toString();
                String t = extras.getString(Notification.EXTRA_TITLE, "");
                if (TextEmpty(t)) {
                    CharSequence convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
                    if (convTitle != null && convTitle.length() > 0) nc.title = convTitle.toString();
                } else {
                    nc.title = t;
                }
                if (TextEmpty(nc.title)) nc.title = resolveAppLabel(sbn.getPackageName());
                if (TextEmpty(nc.content)) nc.content = "新消息";
                return nc;
            }
        } catch (Throwable ignored) {
        }
        // 3. BigText / 普通
        CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (bigText != null && bigText.length() > 0) {
            nc.content = bigText.toString();
        } else if (text != null && text.length() > 0) {
            nc.content = text.toString();
        }
        nc.title = extras.getString(Notification.EXTRA_TITLE, "");
        if (TextEmpty(nc.title)) nc.title = resolveAppLabel(sbn.getPackageName());
        if (TextEmpty(nc.content)) nc.content = "新消息";
        return nc;
    }

    private static boolean TextEmpty(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String resolveAppLabel(String pkg) {
        String cached = mLabelCache.get(pkg);
        if (cached != null) return cached;
        String label = pkg;
        try {
            PackageManager pm = mContext.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            label = pm.getApplicationLabel(ai).toString();
        } catch (Throwable ignored) {
        }
        mLabelCache.put(pkg, label);
        return label;
    }

    // ==================== SbnInfo 构建 ====================

    private CooldownEngine.SbnInfo buildSbnInfo(Settings s, StatusBarNotification sbn) {
        CooldownEngine.SbnInfo info = new CooldownEngine.SbnInfo();
        info.key = sbn.getKey();
        info.pkg = sbn.getPackageName();
        Notification n = sbn.getNotification();
        info.flags = n.flags;
        info.postTime = sbn.getPostTime();
        info.when = n.when;

        if (n.extras != null) {
            info.hasMediaSession = n.extras.containsKey(MEDIA_SESSION_KEY);
            String title = n.extras.getString(Notification.EXTRA_TITLE, "");
            String text = n.extras.getCharSequence(Notification.EXTRA_TEXT, "") != null
                    ? n.extras.getCharSequence(Notification.EXTRA_TEXT).toString() : "";
            String hashSrc = title + "|" + text;
            info.title = TextEmpty(title) ? resolveAppLabel(sbn.getPackageName()) : title;
            info.contentHash = Integer.toHexString(hashSrc.hashCode() & 0x7FFFFFFF);
        } else {
            info.hasMediaSession = false;
            info.title = resolveAppLabel(sbn.getPackageName());
            info.contentHash = Integer.toHexString(("pkg:" + sbn.getPackageName()).hashCode() & 0x7FFFFFFF);
        }

        if (s.skipLowImportance) {
            String channelId = null;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                channelId = n.getChannelId();
            }
            info.channelImportance = queryImportance(channelId, sbn.getPackageName());
        } else {
            info.channelImportance = NotificationManager.IMPORTANCE_DEFAULT;
        }
        return info;
    }

    private int queryImportance(String channelId, String pkg) {
        if (channelId == null) return NotificationManager.IMPORTANCE_DEFAULT;
        String cacheKey = pkg + "/" + channelId;
        Integer cached = mImportanceCache.get(cacheKey);
        if (cached != null) return cached;
        int importance = NotificationManager.IMPORTANCE_DEFAULT;
        try {
            NotificationManager nm = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            android.app.NotificationChannel ch = nm.getNotificationChannel(pkg, channelId);
            if (ch != null) importance = ch.getImportance();
        } catch (Throwable ignored) {
        }
        mImportanceCache.put(cacheKey, importance);
        return importance;
    }

    // ==================== 事件广播（hook 进程 → app 进程） ====================

    private void broadcast(String event, CooldownEngine.SbnInfo info, int reason) {
        try {
            Intent intent = new Intent(ACTION_STAT_EVENT);
            intent.setPackage("com.example.hulfix");
            intent.putExtra(EXTRA_EVENT, event);
            intent.putExtra(EXTRA_PKG, info.pkg);
            intent.putExtra(EXTRA_KEY, info.key);
            String title = info.title == null ? "" : info.title;
            intent.putExtra(EXTRA_TITLE,
                    title.length() > 60 ? title.substring(0, 60) : title);
            intent.putExtra(EXTRA_REASON, reason);
            mContext.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private void log(String msg) {
        Settings s = mSettingsRepo.get();
        if (s != null && s.debugLog) {
            XposedBridge.log("HULFix: " + msg);
        }
    }
}
