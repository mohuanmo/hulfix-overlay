package com.example.hulfix.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.example.hulfix.core.CooldownEngine.Decision;
import com.example.hulfix.core.CooldownEngine.SbnInfo;
import com.example.hulfix.core.CooldownEngine.SceneState;
import com.example.hulfix.core.CooldownEngine.TimeSource;

import org.junit.Before;
import org.junit.Test;

/**
 * 冷却引擎单元测试：覆盖全部冷却 / 限流 / 场景 / 过滤规则。
 */
public class CooldownEngineTest {

    private MessageLedger ledger;
    private CooldownEngine engine;
    private FakeTime time;

    private static final class FakeTime implements TimeSource {
        long elapsed;
        long wall;

        @Override
        public long elapsed() {
            return elapsed;
        }

        @Override
        public long wall() {
            return wall;
        }

        void advance(long ms) {
            elapsed += ms;
            wall += ms;
        }
    }

    private SbnInfo info(String key, String pkg, String hash) {
        SbnInfo i = new SbnInfo();
        i.key = key;
        i.pkg = pkg;
        i.title = "标题";
        i.contentHash = hash;
        i.postTime = time.wall;
        i.when = time.wall;
        return i;
    }

    private SceneState scene() {
        return new SceneState();
    }

    @Before
    public void setUp() {
        ledger = new MessageLedger();
        time = new FakeTime();
        engine = new CooldownEngine(ledger, time);
    }

    // ---------- 基础 ----------

    @Test
    public void defaultSettings_show() {
        Decision d = engine.evaluate(new Settings(), info("k1", "com.a", "h1"), scene());
        assertTrue(d.isShow());
    }

    @Test
    public void disabled_suppresses() {
        Settings s = new Settings();
        s.enabled = false;
        Decision d = engine.evaluate(s, info("k1", "com.a", "h1"), scene());
        assertFalse(d.isShow());
        assertEquals(CooldownEngine.R_DISABLED, d.reason);
    }

    @Test
    public void paused_suppressesUntilResume() {
        Settings s = new Settings();
        s.pauseUntilWallMs = time.wall + 30_000;
        Decision d = engine.evaluate(s, info("k1", "com.a", "h1"), scene());
        assertFalse(d.isShow());
        assertEquals(CooldownEngine.R_PAUSED, d.reason);
        assertTrue(d.retryAfterMs > 0);

        time.advance(31_000);
        s.pauseUntilWallMs = 0;
        assertTrue(engine.evaluate(s, info("k1", "com.a", "h1"), scene()).isShow());
    }

    // ---------- 消息去重（用户核心痛点） ----------

    @Test
    public void sameContentWithinFreshWindow_suppressedAsDuplicate() {
        Settings s = new Settings();
        SbnInfo i = info("k1", "com.a", "h1");
        assertTrue(engine.evaluate(s, i, scene()).isShow());
        engine.onShown(s, i);

        // 同一通知被第二个 hook 点重复读出（同 key 同内容）
        Decision d = engine.evaluate(s, info("k1", "com.a", "h1"), scene());
        assertFalse(d.isShow());
        assertEquals(CooldownEngine.R_DUPLICATE_SAME_CONTENT, d.reason);
    }

    @Test
    public void changedContentStillBlockedByKeyCooldown() {
        Settings s = new Settings();
        s.keyCooldownMs = 3000;
        SbnInfo i = info("k1", "com.a", "h1");
        engine.evaluate(s, i, scene());
        engine.onShown(s, i);

        // 内容更新（hash 变化）但仍在 key 冷却内
        Decision d = engine.evaluate(s, info("k1", "com.a", "h2"), scene());
        assertFalse(d.isShow());
        assertEquals(CooldownEngine.R_KEY_COOLDOWN, d.reason);

        // 冷却过后允许再次弹出
        time.advance(3100);
        assertTrue(engine.evaluate(s, info("k1", "com.a", "h2"), scene()).isShow());
    }

    @Test
    public void multipleHookPoints_sameNotification_onlyShowsOnce() {
        Settings s = new Settings();
        SbnInfo i = info("k1", "com.a", "h1");
        int shown = 0;
        for (int n = 0; n < 5; n++) {
            // 模拟 5 个 hook 入口点连续触发同一条通知
            if (engine.evaluate(s, info("k1", "com.a", "h1"), scene()).isShow()) {
                shown++;
                engine.onShown(s, i);
            } else {
                engine.onSuppressed(s, i, CooldownEngine.R_DUPLICATE_SAME_CONTENT);
            }
        }
        assertEquals(1, shown);
    }

    // ---------- 冷却 ----------

    @Test
    public void globalCooldown_blocksSecondApp() {
        Settings s = new Settings();
        s.globalCooldownMs = 1000;
        SbnInfo a = info("k1", "com.a", "h1");
        assertTrue(engine.evaluate(s, a, scene()).isShow());
        engine.onShown(s, a);

        Decision d = engine.evaluate(s, info("k2", "com.b", "h2"), scene());
        assertFalse(d.isShow());
        assertEquals(CooldownEngine.R_GLOBAL_COOLDOWN, d.reason);

        time.advance(1100);
        assertTrue(engine.evaluate(s, info("k2", "com.b", "h2"), scene()).isShow());
    }

    @Test
    public void appCooldown_blocksSameApp() {
        Settings s = new Settings();
        s.appCooldownMs = 2000;
        SbnInfo a = info("k1", "com.a", "h1");
        engine.evaluate(s, a, scene());
        engine.onShown(s, a);

        Decision d = engine.evaluate(s, info("k2", "com.a", "h2"), scene());
        assertFalse(d.isShow());
        assertEquals(CooldownEngine.R_APP_COOLDOWN, d.reason);
    }

    // ---------- 限流 ----------

    @Test
    public void burstLimit_thirdBlocked() {
        Settings s = new Settings();
        s.burstLimit = 2;
        s.burstWindowMs = 10_000;
        s.globalCooldownMs = 0;
        s.keyCooldownMs = 0;

        for (int n = 1; n <= 2; n++) {
            SbnInfo i = info("k" + n, "com.a", "h" + n);
            assertTrue(engine.evaluate(s, i, scene()).isShow());
            engine.onShown(s, i);
        }
        SbnInfo third = info("k3", "com.a", "h3");
        Decision d = engine.evaluate(s, third, scene());
        assertFalse(d.isShow());
        assertEquals(CooldownEngine.R_BURST_LIMIT, d.reason);
    }

    @Test
    public void burstWindowExpires() {
        Settings s = new Settings();
        s.burstLimit = 2;
        s.burstWindowMs = 10_000;
        s.globalCooldownMs = 0;
        s.keyCooldownMs = 0;
        for (int n = 1; n <= 2; n++) {
            SbnInfo i = info("k" + n, "com.a", "h" + n);
            engine.evaluate(s, i, scene());
            engine.onShown(s, i);
        }
        time.advance(11_000);
        assertTrue(engine.evaluate(s, info("k3", "com.a", "h3"), scene()).isShow());
    }

    @Test
    public void minuteLimit() {
        Settings s = new Settings();
        s.minuteLimit = 3;
        s.globalCooldownMs = 0;
        s.keyCooldownMs = 0;
        s.burstLimit = 0;
        for (int n = 1; n <= 3; n++) {
            SbnInfo i = info("k" + n, "com.a", "h" + n);
            assertTrue(engine.evaluate(s, i, scene()).isShow());
            engine.onShown(s, i);
        }
        Decision d = engine.evaluate(s, info("k4", "com.a", "h4"), scene());
        assertFalse(d.isShow());
        assertEquals(CooldownEngine.R_MINUTE_LIMIT, d.reason);
    }

    // ---------- 用户划掉 ----------

    @Test
    public void userDismissed_keyScope() {
        Settings s = new Settings();
        s.userDismissCooldownMs = 60_000;
        s.globalCooldownMs = 0; // 隔离全局冷却，单独验证 key 范围划掉
        SbnInfo a = info("k1", "com.a", "h1");
        engine.evaluate(s, a, scene());
        engine.onShown(s, a);
        engine.onUserDismissed(s, a);

        // 同 key 被拦
        Decision d = engine.evaluate(s, info("k1", "com.a", "h2"), scene());
        assertFalse(d.isShow());
        assertEquals(CooldownEngine.R_USER_DISMISSED, d.reason);

        // 同应用其他 key 不受影响
        assertTrue(engine.evaluate(s, info("k2", "com.a", "h9"), scene()).isShow());
    }

    @Test
    public void userDismissed_appScope() {
        Settings s = new Settings();
        s.userDismissCooldownMs = 60_000;
        s.userDismissScope = Settings.SCOPE_APP;
        s.globalCooldownMs = 0;
        s.keyCooldownMs = 0;
        SbnInfo a = info("k1", "com.a", "h1");
        engine.evaluate(s, a, scene());
        engine.onShown(s, a);
        engine.onUserDismissed(s, a);

        Decision d = engine.evaluate(s, info("k2", "com.a", "h2"), scene());
        assertFalse(d.isShow());
        assertEquals(CooldownEngine.R_USER_DISMISSED, d.reason);
    }

    @Test
    public void userDismissed_persistedIgnoredList() {
        Settings s = new Settings();
        s.userDismissCooldownMs = 60_000;
        s.ignoredEntries.add(new Settings.IgnoredEntry("k1", "com.a", "标题",
                time.wall));
        Decision d = engine.evaluate(s, info("k1", "com.a", "h1"), scene());
        assertFalse(d.isShow());
        assertEquals(CooldownEngine.R_USER_DISMISSED, d.reason);
    }

    // ---------- 场景 ----------

    @Test
    public void scenes() {
        Settings s = new Settings();
        SbnInfo i = info("k1", "com.a", "h1");

        SceneState dnd = scene();
        dnd.dnd = true;
        assertEquals(CooldownEngine.R_DND,
                engine.evaluate(s, i, dnd).reason);

        SceneState kg = scene();
        kg.keyguard = true;
        assertEquals(CooldownEngine.R_KEYGUARD, engine.evaluate(s, i, kg).reason);

        SceneState panel = scene();
        panel.panelExpanded = true;
        assertEquals(CooldownEngine.R_PANEL_EXPANDED,
                engine.evaluate(s, i, panel).reason);

        SceneState off = scene();
        off.screenOff = true;
        assertEquals(CooldownEngine.R_SCREEN_OFF,
                engine.evaluate(s, i, off).reason);

        SceneState call = scene();
        call.inCall = true;
        assertEquals(CooldownEngine.R_IN_CALL, engine.evaluate(s, i, call).reason);

        SceneState fg = scene();
        fg.foregroundPkg = "com.game";
        s.foregroundSuppressApps.add("com.game");
        assertEquals(CooldownEngine.R_FG_SUPPRESSED,
                engine.evaluate(s, i, fg).reason);
    }

    // ---------- 通知属性 ----------

    @Test
    public void notificationFlags() {
        Settings s = new Settings();
        SbnInfo i = info("k1", "com.a", "h1");

        i.flags = android.app.Notification.FLAG_ONGOING_EVENT;
        assertEquals(CooldownEngine.R_ONGOING, engine.evaluate(s, i, scene()).reason);
        i.flags = 0;

        i.flags = android.app.Notification.FLAG_FOREGROUND_SERVICE;
        assertEquals(CooldownEngine.R_FGS, engine.evaluate(s, i, scene()).reason);
        i.flags = 0;

        i.hasMediaSession = true;
        s.skipMedia = true;
        assertEquals(CooldownEngine.R_MEDIA, engine.evaluate(s, i, scene()).reason);
        i.hasMediaSession = false;

        i.channelImportance = android.app.NotificationManager.IMPORTANCE_LOW;
        s.skipLowImportance = true;
        assertEquals(CooldownEngine.R_LOW_IMPORTANCE,
                engine.evaluate(s, i, scene()).reason);
    }

    // ---------- 过滤 ----------

    @Test
    public void filterModes() {
        Settings s = new Settings();
        s.blockedApps.add("com.a");
        SbnInfo a = info("k1", "com.a", "h1");
        SbnInfo b = info("k2", "com.b", "h2");
        assertEquals(CooldownEngine.R_BLOCKED, engine.evaluate(s, a, scene()).reason);
        assertTrue(engine.evaluate(s, b, scene()).isShow());

        s.filterMode = Settings.FILTER_WHITELIST;
        s.allowedApps.add("com.a");
        assertTrue(engine.evaluate(s, a, scene()).isShow());
        assertEquals(CooldownEngine.R_NOT_ALLOWED,
                engine.evaluate(s, b, scene()).reason);
    }

    // ---------- 过期 ----------

    @Test
    public void staleNotification() {
        Settings s = new Settings();
        time.advance(100_000); // 先把墙钟推到正值，避免 0 基线造成负时间戳
        SbnInfo i = info("k1", "com.a", "h1");
        i.when = time.wall - 30_000;
        i.postTime = time.wall - 30_000;
        Decision d = engine.evaluate(s, i, scene());
        assertFalse(d.isShow());
        assertEquals(CooldownEngine.R_STALE, d.reason);
    }

    // ---------- 引擎关闭 ----------

    @Test
    public void engineDisabled_skipsCooldownsButKeepsHardRules() {
        Settings s = new Settings();
        s.engineEnabled = false;
        s.burstLimit = 1;
        SbnInfo a = info("k1", "com.a", "h1");
        assertTrue(engine.evaluate(s, a, scene()).isShow());
        engine.onShown(s, a);
        // 冷却规则不生效
        assertTrue(engine.evaluate(s, info("k2", "com.b", "h2"), scene()).isShow());
        // 但黑名单仍然生效
        s.blockedApps.add("com.b");
        assertEquals(CooldownEngine.R_BLOCKED,
                engine.evaluate(s, info("k3", "com.b", "h3"), scene()).reason);
    }

    // ---------- 统计 ----------

    @Test
    public void stats() {
        Settings s = new Settings();
        SbnInfo a = info("k1", "com.a", "h1");
        engine.evaluate(s, a, scene());
        engine.onShown(s, a);
        engine.onSuppressed(s, a, CooldownEngine.R_KEY_COOLDOWN);
        int[] stats = engine.stats();
        assertEquals(1, stats[0]);
        assertEquals(1, stats[1]);
        assertNotNull(stats);
    }
}
