package com.example.hulfix.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 冷却引擎（Cooldown Engine）。
 *
 * <p>HULFix 的所有"要不要现在弹"的判断统一收敛到本引擎：</p>
 *
 * <ol>
 *   <li><b>手动暂停</b>：用户在设置页一键暂停弹窗发送（带时长），到期自动恢复；</li>
 *   <li><b>场景阻断</b>：熄屏 / 免打扰 / 锁屏 / 通知面板展开 / 通话中 / 前台抑制应用；</li>
 *   <li><b>通知自身属性</b>：常驻 / 前台服务 / 媒体 / 低优先级频道 / 过期通知；</li>
 *   <li><b>应用过滤</b>：黑名单 / 白名单；</li>
 *   <li><b>消息去重（MessageLedger）</b>：同 key 同内容的重复读出直接抑制；</li>
 *   <li><b>冷却限流</b>：同 key 冷却 / 同应用冷却 / 全局冷却 / 突发窗口限流 / 每分钟限流；</li>
 *   <li><b>用户划掉冷却</b>：按 key / 应用 / 全局三种范围。</li>
 * </ol>
 *
 * <p>引擎本身不持有 Android 依赖，时间通过 {@link TimeSource} 注入，便于 JVM 单元测试。</p>
 */
public final class CooldownEngine {

    /** 时间源（运行时可换，测试用假时钟）。 */
    public interface TimeSource {
        long elapsed();  // 单调时钟（SystemClock.elapsedRealtime 语义）
        long wall();     // 墙钟（System.currentTimeMillis 语义）
    }

    // ==================== 决策结果 ====================

    public static final int DECISION_SHOW = 0;
    public static final int DECISION_SUPPRESS = 1;

    // 抑制原因（同步暴露给设置页展示）
    public static final int R_DISABLED = 1;
    public static final int R_PAUSED = 2;
    public static final int R_SCREEN_OFF = 3;
    public static final int R_DND = 4;
    public static final int R_KEYGUARD = 5;
    public static final int R_PANEL_EXPANDED = 6;
    public static final int R_IN_CALL = 7;
    public static final int R_FG_SUPPRESSED = 8;
    public static final int R_ONGOING = 9;
    public static final int R_FGS = 10;
    public static final int R_MEDIA = 11;
    public static final int R_LOW_IMPORTANCE = 12;
    public static final int R_STALE = 13;
    public static final int R_BLOCKED = 14;
    public static final int R_NOT_ALLOWED = 15;
    public static final int R_DUPLICATE_SAME_CONTENT = 16;
    public static final int R_KEY_COOLDOWN = 17;
    public static final int R_APP_COOLDOWN = 18;
    public static final int R_GLOBAL_COOLDOWN = 19;
    public static final int R_BURST_LIMIT = 20;
    public static final int R_MINUTE_LIMIT = 21;
    public static final int R_USER_DISMISSED = 22;

    public static final class Decision {
        public int result = DECISION_SUPPRESS;
        public int reason = R_DISABLED;
        /** 预计多久后可以再次尝试（ms；仅抑制时有意义，-1 = 未知）。 */
        public long retryAfterMs = -1;

        public boolean isShow() {
            return result == DECISION_SHOW;
        }

        public static Decision show() {
            Decision d = new Decision();
            d.result = DECISION_SHOW;
            return d;
        }

        public static Decision suppress(int reason, long retryAfterMs) {
            Decision d = new Decision();
            d.result = DECISION_SUPPRESS;
            d.reason = reason;
            d.retryAfterMs = retryAfterMs;
            return d;
        }
    }

    // ==================== 输入快照 ====================

    /** 从 StatusBarNotification 提取的轻量信息（避免引擎直接依赖 framework 类型）。 */
    public static final class SbnInfo {
        public String key = "";
        public String pkg = "";
        public String title = "";
        public String contentHash = "";
        public int flags;
        public boolean hasMediaSession;
        public int channelImportance = 3; // NotificationManager.IMPORTANCE_DEFAULT
        public long postTime;
        public long when;
    }

    /** 场景快照（由 SceneDetector 提供，带缓存）。 */
    public static final class SceneState {
        public boolean dnd;
        public boolean keyguard;
        public boolean panelExpanded;
        public boolean screenOff;
        public boolean inCall;
        public String foregroundPkg = "";
    }

    /** 通知保鲜期：超过该时长的迟到通知直接丢弃（原 NOTIFICATION_MAX_AGE_MS 语义）。 */
    public static final long FRESH_MAX_AGE_MS = 8000L;

    // ==================== 状态 ====================

    private final MessageLedger mLedger;
    private final TimeSource mTime;

    private long mLastAnyShowElapsed = -1;              // 全局冷却基准（-1 = 从未展示）
    private final Map<String, Long> mAppLastShow;     // 每应用最近展示
    private final Map<String, Long> mAppLastDismiss;  // 每应用最近用户划掉
    private long mGlobalLastDismiss = -1;              // 全局范围用户划掉基准（-1 = 从未划掉）
    private final Deque<Long> mRecentShows;           // 突发窗口内的展示时间戳

    /** 内存统计（App 端镜像由广播驱动另行维护）。 */
    public volatile int shownTotal;
    public volatile int suppressedTotal;

    public CooldownEngine(MessageLedger ledger, TimeSource time) {
        mLedger = ledger;
        mTime = time;
        mAppLastShow = new HashMap<>();
        mAppLastDismiss = new HashMap<>();
        mRecentShows = new ArrayDeque<>();
    }

    // ==================== 主入口：评估一条通知 ====================

    public Decision evaluate(Settings s, SbnInfo info, SceneState scene) {
        // 1. 总开关
        if (!s.enabled) return Decision.suppress(R_DISABLED, -1);

        // 2. 手动暂停
        if (isPaused(s)) return Decision.suppress(R_PAUSED, pauseRemainingMs(s));

        // 3. 场景阻断（按成本从低到高）
        if (scene != null) {
            if (s.blockWhenScreenOff && scene.screenOff) return Decision.suppress(R_SCREEN_OFF, -1);
            if (s.blockWhenDnd && scene.dnd) return Decision.suppress(R_DND, -1);
            if (s.blockWhenKeyguard && scene.keyguard) return Decision.suppress(R_KEYGUARD, -1);
            if (s.blockWhenPanelExpanded && scene.panelExpanded) return Decision.suppress(R_PANEL_EXPANDED, -1);
            if (s.blockWhenInCall && scene.inCall) return Decision.suppress(R_IN_CALL, -1);
            if (!s.foregroundSuppressApps.isEmpty()
                    && s.foregroundSuppressApps.contains(scene.foregroundPkg)) {
                return Decision.suppress(R_FG_SUPPRESSED, -1);
            }
        }

        // 4. 通知自身属性
        if (s.skipOngoing && (info.flags & android.app.Notification.FLAG_ONGOING_EVENT) != 0) {
            return Decision.suppress(R_ONGOING, -1);
        }
        if (s.skipForegroundService && (info.flags & android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0) {
            return Decision.suppress(R_FGS, -1);
        }
        if (s.skipMedia && info.hasMediaSession) return Decision.suppress(R_MEDIA, -1);
        if (s.skipLowImportance && info.channelImportance
                < android.app.NotificationManager.IMPORTANCE_DEFAULT) {
            return Decision.suppress(R_LOW_IMPORTANCE, -1);
        }

        // 5. 过期通知（墙钟）
        long nowWall = mTime.wall();
        long reference = info.when > 0 ? Math.max(info.when, info.postTime) : info.postTime;
        if (reference > 0 && nowWall - reference > FRESH_MAX_AGE_MS) {
            return Decision.suppress(R_STALE, -1);
        }

        // 6. 应用过滤
        if (s.filterMode == Settings.FILTER_WHITELIST) {
            if (!s.allowedApps.contains(info.pkg)) return Decision.suppress(R_NOT_ALLOWED, -1);
        } else if (s.filterMode == Settings.FILTER_BLACKLIST) {
            if (s.blockedApps.contains(info.pkg)) return Decision.suppress(R_BLOCKED, -1);
        }

        // 7. 引擎关闭时只保留以上"硬规则"
        if (!s.engineEnabled) return Decision.show();

        long now = mTime.elapsed();

        // 台账记录（用户划掉 / 去重 / key 冷却共用）
        MessageLedger.MessageRecord rec = s.ledgerEnabled ? mLedger.get(info.key) : null;

        // 8. 用户划掉冷却（最高优先级引擎规则：显式操作强于时间冷却；
        //    运行时台账 + 持久化忽略列表 + 应用/全局范围，三个来源都查）
        if (s.userDismissCooldownMs > 0) {
            if (rec != null && rec.userDismissedElapsedMs >= 0) {
                long since = now - rec.userDismissedElapsedMs;
                if (since < s.userDismissCooldownMs) {
                    return Decision.suppress(R_USER_DISMISSED, s.userDismissCooldownMs - since);
                }
            }
            // 持久化忽略列表（跨重启）
            for (Settings.IgnoredEntry e : s.ignoredEntries) {
                if (!info.key.equals(e.key)) continue;
                long sinceWall = nowWall - e.atWallMs;
                if (sinceWall >= 0 && sinceWall < s.userDismissCooldownMs) {
                    return Decision.suppress(R_USER_DISMISSED, s.userDismissCooldownMs - sinceWall);
                }
            }
            // 应用 / 全局范围
            if (s.userDismissScope == Settings.SCOPE_APP) {
                Long last = mAppLastDismiss.get(info.pkg);
                if (last != null) {
                    long since = now - last;
                    if (since < s.userDismissCooldownMs) {
                        return Decision.suppress(R_USER_DISMISSED, s.userDismissCooldownMs - since);
                    }
                }
            } else if (s.userDismissScope == Settings.SCOPE_GLOBAL) {
                long since = now - mGlobalLastDismiss;
                if (mGlobalLastDismiss >= 0 && since < s.userDismissCooldownMs) {
                    return Decision.suppress(R_USER_DISMISSED, s.userDismissCooldownMs - since);
                }
            }
        }

        // 9. 台账：重复读出去重（多 hook 点 / 同消息重复进管线的核心防线）
        if (rec != null && s.dedupSameContent
                && rec.showCount > 0
                && info.contentHash.equals(rec.lastContentHash)
                && now - rec.lastShownElapsedMs < MessageLedger.FRESH_WINDOW_MS) {
            long retry = MessageLedger.FRESH_WINDOW_MS - (now - rec.lastShownElapsedMs);
            return Decision.suppress(R_DUPLICATE_SAME_CONTENT, retry);
        }

        // 10. 同 key 冷却
        if (rec != null && s.keyCooldownMs > 0 && rec.lastShownElapsedMs >= 0) {
            long since = now - rec.lastShownElapsedMs;
            if (since < s.keyCooldownMs) return Decision.suppress(R_KEY_COOLDOWN, s.keyCooldownMs - since);
        }

        // 11. 同应用冷却
        if (s.appCooldownMs > 0) {
            Long last = mAppLastShow.get(info.pkg);
            if (last != null) {
                long since = now - last;
                if (since < s.appCooldownMs) {
                    return Decision.suppress(R_APP_COOLDOWN, s.appCooldownMs - since);
                }
            }
        }

        // 12. 全局冷却
        if (s.globalCooldownMs > 0 && mLastAnyShowElapsed >= 0) {
            long since = now - mLastAnyShowElapsed;
            if (since < s.globalCooldownMs) {
                return Decision.suppress(R_GLOBAL_COOLDOWN, s.globalCooldownMs - since);
            }
        }

        // 13. 突发窗口限流
        if (s.burstLimit > 0 && s.burstWindowMs > 0) {
            pruneRecent(now, s.burstWindowMs);
            if (mRecentShows.size() >= s.burstLimit) {
                long oldest = mRecentShows.peekFirst();
                return Decision.suppress(R_BURST_LIMIT, oldest + s.burstWindowMs - now);
            }
        }

        // 14. 每分钟限流
        if (s.minuteLimit > 0) {
            pruneRecent(now, 60_000L);
            if (mRecentShows.size() >= s.minuteLimit) {
                long oldest = mRecentShows.peekFirst();
                return Decision.suppress(R_MINUTE_LIMIT, oldest + 60_000L - now);
            }
        }

        // 14. 走到这里：放行
        return Decision.show();
    }

    // ==================== 事件回报 ====================

    /** 成功展示后调用：更新冷却基准与台账。 */
    public void onShown(Settings s, SbnInfo info) {
        long now = mTime.elapsed();
        long wall = mTime.wall();
        mLastAnyShowElapsed = now;
        mAppLastShow.put(info.pkg, now);
        pruneRecent(now, 60_000L);
        mRecentShows.addLast(now);
        shownTotal++;
        if (s.ledgerEnabled) {
            MessageLedger.MessageRecord rec = mLedger.touch(info.key, info.pkg, info.title);
            mLedger.recordShown(rec, info.contentHash, now, wall);
        }
    }

    /** 被抑制时调用：仅累计台账统计。 */
    public void onSuppressed(Settings s, SbnInfo info, int reason) {
        suppressedTotal++;
        if (s.ledgerEnabled) {
            MessageLedger.MessageRecord rec = mLedger.touch(info.key, info.pkg, info.title);
            mLedger.recordSuppressed(rec, reason, mTime.elapsed());
        }
    }

    /** 用户划掉/忽略后调用：记录冷却基准。 */
    public void onUserDismissed(Settings s, SbnInfo info) {
        long now = mTime.elapsed();
        long wall = mTime.wall();
        if (s.userDismissScope == Settings.SCOPE_APP) {
            mAppLastDismiss.put(info.pkg, now);
        } else if (s.userDismissScope == Settings.SCOPE_GLOBAL) {
            mGlobalLastDismiss = now;
        }
        if (s.ledgerEnabled) {
            MessageLedger.MessageRecord rec = mLedger.touch(info.key, info.pkg, info.title);
            mLedger.recordUserDismissed(rec, now, wall);
        }
    }

    // ==================== 手动暂停 ====================

    public boolean isPaused(Settings s) {
        return pauseRemainingMs(s) > 0;
    }

    public long pauseRemainingMs(Settings s) {
        if (s.pauseUntilWallMs <= 0) return 0;
        long remain = s.pauseUntilWallMs - mTime.wall();
        return Math.max(0, remain);
    }

    // ==================== 统计 ====================

    /** 供设置页展示：返回 [总展示, 总抑制, 台账条数]。 */
    public int[] stats() {
        return new int[]{shownTotal, suppressedTotal, mLedger.size()};
    }

    public void resetStats() {
        shownTotal = 0;
        suppressedTotal = 0;
    }

    // ==================== 内部 ====================

    private void pruneRecent(long now, long windowMs) {
        while (!mRecentShows.isEmpty()) {
            Long head = mRecentShows.peekFirst();
            if (head == null || now - head > windowMs) {
                mRecentShows.pollFirst();
            } else {
                break;
            }
        }
    }

    /** 把抑制原因码转成中文说明（设置页展示）。 */
    public static String reasonLabel(int reason) {
        switch (reason) {
            case R_DISABLED: return "模块已关闭";
            case R_PAUSED: return "已手动暂停";
            case R_SCREEN_OFF: return "熄屏中";
            case R_DND: return "免打扰中";
            case R_KEYGUARD: return "锁屏中";
            case R_PANEL_EXPANDED: return "通知栏展开";
            case R_IN_CALL: return "通话中";
            case R_FG_SUPPRESSED: return "前台应用抑制";
            case R_ONGOING: return "常驻通知";
            case R_FGS: return "前台服务通知";
            case R_MEDIA: return "媒体播放通知";
            case R_LOW_IMPORTANCE: return "低优先级频道";
            case R_STALE: return "过期通知";
            case R_BLOCKED: return "黑名单应用";
            case R_NOT_ALLOWED: return "不在白名单";
            case R_DUPLICATE_SAME_CONTENT: return "重复消息";
            case R_KEY_COOLDOWN: return "同消息冷却中";
            case R_APP_COOLDOWN: return "同应用冷却中";
            case R_GLOBAL_COOLDOWN: return "全局冷却中";
            case R_BURST_LIMIT: return "突发限流";
            case R_MINUTE_LIMIT: return "每分钟限流";
            case R_USER_DISMISSED: return "已划掉忽略";
            default: return "未知";
        }
    }
}
