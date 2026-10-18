package com.example.hulfix.core;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.List;

/**
 * 场景检测（SceneDetector）。
 *
 * <p>把"现在适不适合弹窗"的所有环境量集中在一处，带 TTL 缓存 + 系统广播即时刷新，
 * 避免每条通知都做昂贵的 binder 查询。查询成本从低到高依次为：</p>
 *
 * <p>熄屏（本地标志）&lt; 通知面板展开（hook 维护标志）&lt; 免打扰（receiver+TTL）&lt;
 * 锁屏（TTL）&lt; 通话（AudioManager TTL）&lt; 前台应用（getRunningTasks，仅当配置了
 * 前台抑制列表时才查）。</p>
 */
public final class SceneDetector {

    private static final long TTL_DND = 1000L;
    private static final long TTL_KEYGUARD = 400L;
    private static final long TTL_IN_CALL = 400L;
    private static final long TTL_SCREEN = 400L;
    private static final long TTL_FOREGROUND = 800L;

    /**
     * 主线程 Handler：惰性创建。模块类在 zygote fork 阶段（主 Looper 就绪前）就会
     * 实例化，构造期内 {@code new Handler(Looper.getMainLooper())} 会 NPE。
     */
    private final MainHandler mHandler = new MainHandler();
    private Context mContext;

    // 本地维护状态（零成本）
    private volatile boolean mPanelExpanded;
    private volatile boolean mScreenOffFlag;
    private boolean mScreenReceiverRegistered;

    // TTL 缓存
    private volatile boolean mDnd;
    private volatile boolean mKeyguard;
    private volatile boolean mInCall;
    private volatile boolean mScreenOn;
    private volatile String mForegroundPkg = "";
    private long mDndAt;
    private long mKeyguardAt;
    private long mInCallAt;
    private long mScreenAt;
    private long mFgAt;

    public SceneDetector() {
    }

    public void init(Context context) {
        mContext = context;
        registerScreenReceiver(context);
    }

    /** 由 hook 侧维护：通知面板展开/收起。 */
    public void setPanelExpanded(boolean expanded) {
        mPanelExpanded = expanded;
    }

    public boolean isPanelExpanded() {
        return mPanelExpanded;
    }

    // ==================== 快照 ====================

    public CooldownEngine.SceneState snapshot(Settings s) {
        CooldownEngine.SceneState st = new CooldownEngine.SceneState();
        long now = SystemClock.elapsedRealtime();

        st.screenOff = isScreenOff(s, now);
        st.panelExpanded = mPanelExpanded;
        st.dnd = s.blockWhenDnd && isDnd(now);
        st.keyguard = s.blockWhenKeyguard && isKeyguard(now);
        st.inCall = s.blockWhenInCall && isInCall(now);

        if (!s.foregroundSuppressApps.isEmpty()) {
            st.foregroundPkg = getForegroundPkg(now);
        }
        return st;
    }

    // ==================== 各场景实现 ====================

    private boolean isScreenOff(Settings s, long now) {
        if (mScreenOffFlag) return true;
        if (!s.blockWhenScreenOff) return false;
        if (now - mScreenAt >= TTL_SCREEN) {
            mScreenAt = now;
            try {
                PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                mScreenOn = pm != null && pm.isInteractive();
            } catch (Throwable t) {
                mScreenOn = true; // 查询失败时不阻断
            }
        }
        return !mScreenOn;
    }

    private boolean isDnd(long now) {
        if (now - mDndAt >= TTL_DND) {
            mDndAt = now;
            try {
                NotificationManager nm =
                        (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    int filter = nm.getCurrentInterruptionFilter();
                    mDnd = filter == NotificationManager.INTERRUPTION_FILTER_NONE
                            || filter == NotificationManager.INTERRUPTION_FILTER_ALARMS;
                }
            } catch (Throwable t) {
                mDnd = false;
            }
        }
        return mDnd;
    }

    private boolean isKeyguard(long now) {
        if (now - mKeyguardAt >= TTL_KEYGUARD) {
            mKeyguardAt = now;
            try {
                KeyguardManager km = (KeyguardManager) mContext.getSystemService(Context.KEYGUARD_SERVICE);
                mKeyguard = km != null && km.isKeyguardLocked();
            } catch (Throwable t) {
                mKeyguard = false;
            }
        }
        return mKeyguard;
    }

    private boolean isInCall(long now) {
        if (now - mInCallAt >= TTL_IN_CALL) {
            mInCallAt = now;
            try {
                AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
                mInCall = am != null && am.getMode() != AudioManager.MODE_NORMAL;
            } catch (Throwable t) {
                mInCall = false;
            }
        }
        return mInCall;
    }

    private String getForegroundPkg(long now) {
        if (now - mFgAt >= TTL_FOREGROUND) {
            mFgAt = now;
            try {
                android.app.ActivityManager am = (android.app.ActivityManager)
                        mContext.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    List<android.app.ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                    if (tasks != null && !tasks.isEmpty() && tasks.get(0).topActivity != null) {
                        mForegroundPkg = tasks.get(0).topActivity.getPackageName();
                    }
                }
            } catch (Throwable t) {
                // 保留上一次结果
            }
        }
        return mForegroundPkg;
    }

    // ==================== 广播 ====================

    private void registerScreenReceiver(Context context) {
        if (mScreenReceiverRegistered) return;
        try {
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        mScreenOffFlag = true;
                        mScreenOn = false;
                        mScreenAt = 0;
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        mScreenOffFlag = false;
                        mScreenAt = 0;
                    } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                        mScreenOffFlag = false;
                        mKeyguardAt = 0; // 强制刷新锁屏状态
                        mScreenAt = 0;
                    } else if (NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED.equals(action)) {
                        mDndAt = 0; // 强制刷新免打扰状态
                    }
                }
            };
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
            // 全部为受保护的系统广播：NOT_EXPORTED 即可收到，且满足 Android 14 的 flag 强制要求
            PlatformCompat.registerReceiver(context, receiver, filter,
                    mHandler.get(), false);
            mScreenReceiverRegistered = true;
        } catch (Throwable ignored) {
        }
    }
}
