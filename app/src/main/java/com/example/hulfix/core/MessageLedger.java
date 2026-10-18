package com.example.hulfix.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息管理台账（Message Ledger）。
 *
 * <p>SystemUI 内部同一个通知会经过多个入口点（NotificationEntry 构造 / updateNotification /
 * NotifPipeline.addNotification / NotificationListener.onNotificationPosted ...），同一条消息
 * 可能被读出多遍。本台账以通知 key 为唯一索引，记录每条消息的展示历史、抑制历史与用户
 * 交互历史，是"同一条消息不重复弹出"的核心防线：</p>
 *
 * <ul>
 *   <li>同 key + 同内容哈希 + 仍在保鲜期内 → 判定为重复读出，直接抑制；</li>
 *   <li>同 key 两次展示间隔 &lt; key 冷却 → 抑制；</li>
 *   <li>用户划掉过的 key（持久化）→ 在冷却期内抑制；</li>
 *   <li>容量受限的 LRU，防止长时间运行内存膨胀。</li>
 * </ul>
 *
 * <p>纯内存 + 可 JSON 序列化（供 App 端设置页展示镜像）。不依赖 Android framework。</p>
 */
public final class MessageLedger {

    /** 台账最大条数（LRU 淘汰）。 */
    public static final int CAPACITY = 256;

    /** 展示保鲜期：同 key 同内容在此时间内再次到来视为重复读出。 */
    public static final long FRESH_WINDOW_MS = 10_000L;

    private final LinkedHashMap<String, MessageRecord> mRecords;

    public MessageLedger() {
        mRecords = new LinkedHashMap<>(64, 0.75f, true);
    }

    // ==================== 查询 ====================

    public MessageRecord get(String key) {
        if (key == null) return null;
        synchronized (mRecords) {
            return mRecords.get(key);
        }
    }

    public int size() {
        synchronized (mRecords) {
            return mRecords.size();
        }
    }

    /** 按最近活跃时间倒序的快照（最新在前）。 */
    public List<MessageRecord> snapshotRecent(int max) {
        List<MessageRecord> all = new ArrayList<>();
        synchronized (mRecords) {
            for (MessageRecord r : mRecords.values()) {
                if (r.lastEventElapsedMs > 0) all.add(r);
            }
        }
        all.sort((a, b) -> Long.compare(b.lastEventElapsedMs, a.lastEventElapsedMs));
        if (all.size() > max) {
            return new ArrayList<>(all.subList(0, max));
        }
        return all;
    }

    // ==================== 事件记录 ====================

    /** 首次见到某条消息（尚未决定是否展示）。 */
    public MessageRecord touch(String key, String pkg, String title) {
        if (key == null) return null;
        MessageRecord r;
        synchronized (mRecords) {
            r = mRecords.get(key);
            if (r == null) {
                r = new MessageRecord();
                r.key = key;
                mRecords.put(key, r);
                trimLocked();
            }
            r.pkg = pkg;
            r.title = title;
            r.seenCount++;
        }
        return r;
    }

    public void recordShown(MessageRecord r, String contentHash, long elapsedNow, long wallNow) {
        if (r == null) return;
        synchronized (mRecords) {
            r.showCount++;
            r.suppressedCount = 0; // 展示成功后重置连续抑制计数
            r.lastContentHash = contentHash;
            r.lastShownElapsedMs = elapsedNow;
            r.lastShownWallMs = wallNow;
            r.lastEventElapsedMs = elapsedNow;
            r.lastReason = 0;
        }
    }

    public void recordSuppressed(MessageRecord r, int reason, long elapsedNow) {
        if (r == null) return;
        synchronized (mRecords) {
            r.suppressedCount++;
            r.lastReason = reason;
            r.lastEventElapsedMs = elapsedNow;
        }
    }

    public void recordUserDismissed(MessageRecord r, long elapsedNow, long wallNow) {
        if (r == null) return;
        synchronized (mRecords) {
            r.userDismissedElapsedMs = elapsedNow;
            r.userDismissedWallMs = wallNow;
            r.lastEventElapsedMs = elapsedNow;
        }
    }

    public void clear() {
        synchronized (mRecords) {
            mRecords.clear();
        }
    }

    // ==================== 序列化（App 端镜像） ====================

    public String toJson(int max) {
        JSONArray arr = new JSONArray();
        try {
            for (MessageRecord r : snapshotRecent(max)) {
                JSONObject o = new JSONObject();
                o.put("key", r.key);
                o.put("pkg", r.pkg);
                o.put("title", r.title);
                o.put("seen", r.seenCount);
                o.put("shown", r.showCount);
                o.put("suppressed", r.suppressedCount);
                o.put("reason", r.lastReason);
                o.put("lastShown", r.lastShownWallMs);
                o.put("lastEvent", r.lastEventElapsedMs);
                arr.put(o);
            }
        } catch (JSONException ignored) {
        }
        return arr.toString();
    }

    // ==================== 内部 ====================

    private void trimLocked() {
        // accessOrder=true：迭代顺序即"最久未访问在前"，直接淘汰头部。
        // 注意不能按 lastEventElapsedMs 最小值淘汰——新插入的记录该字段还是
        // 初始值 0，会被误当成"最陈旧"而立刻弹出，导致 get() 返回 null。
        while (mRecords.size() > CAPACITY) {
            java.util.Iterator<Map.Entry<String, MessageRecord>> it =
                    mRecords.entrySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }

    // ==================== 数据结构 ====================

    public static final class MessageRecord {
        public String key;
        public String pkg;
        public String title = "";
        public String lastContentHash = "";
        public int seenCount;
        public int showCount;
        public int suppressedCount;
        public int lastReason;
        public long firstSeenElapsedMs;
        public long lastShownElapsedMs = -1;   // -1 = 从未展示（区别于 elapsed=0 的时刻）
        public long lastShownWallMs;
        public long lastEventElapsedMs;
        public long userDismissedElapsedMs = -1; // -1 = 从未被划掉
        public long userDismissedWallMs;

        /** 是否曾被用户划掉（且仍在冷却期内由引擎判断，这里只记录事实）。 */
        public boolean wasUserDismissed() {
            return userDismissedElapsedMs >= 0;
        }
    }
}
