package com.example.hulfix.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.hulfix.overlay.StyleRegistry;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;

/**
 * {@link Settings} JSON 序列化测试——设置热更新广播通道（App → SystemUI 全量 JSON）的核心。
 *
 * <p>广播通道是"设置修改不重启即生效"的唯一可靠路径（SELinux 下 SystemUI 读不到 App
 * 私有目录），序列化丢一个字段 = 对应设置静默失效，因此逐字段覆盖。</p>
 */
public class SettingsJsonTest {

    private static Settings sample() {
        Settings s = new Settings();
        s.enabled = false;
        s.styleId = StyleRegistry.StyleIds.IOS_LIQUID;
        s.customStylesJson = "[{\"id\":\"custom_1\",\"name\":\"测试\"}]";
        s.themeMode = Settings.THEME_DARK;

        s.enterAnim = Settings.ANIM_SPRING;
        s.exitAnim = Settings.ANIM_FADE;
        s.enterDir = Settings.DIR_LEFT;
        s.enterDurationMs = 123;
        s.enterOvershoot = 1.7f;
        s.exitDurationMs = 234;
        s.staggerContent = false;

        s.gestureHorizontal = false;
        s.gestureVertical = false;
        s.gesturePullDown = false;
        s.gestureTap = false;
        s.gestureLongPress = true;
        s.swipeThresholdPx = 111f;
        s.flingVelocityPxS = 222f;

        s.anchor = Settings.ANCHOR_BOTTOM_RIGHT;
        s.offsetXDp = -12;
        s.offsetYDp = 34;
        s.widthScale = 1.3f;
        s.cornerScale = 0.8f;
        s.bgAlpha = 0.65f;

        s.lensEnabled = false;
        s.lensThickness = 21f;
        s.lensEdge = 0.4f;
        s.lensDispersion = 0.9f;
        s.lensSpecular = 1.5f;
        s.lensIOR = 1.8f;
        s.lensDynamic = false;
        s.lensDynamicStrength = 0.55f;
        s.lensAdaptiveShadow = 0.72f;
        s.lensAccentFollow = true;

        s.autoDismissMs = 4321;
        s.showAppName = false;
        s.maxTextLines = 4;

        s.filterMode = Settings.FILTER_WHITELIST;
        s.blockedApps = new HashSet<>(Arrays.asList("com.a", "com.b"));
        s.allowedApps = new HashSet<>(Arrays.asList("com.c"));
        s.foregroundSuppressApps = new HashSet<>(Arrays.asList("com.d", "com.e"));

        s.blockWhenDnd = false;
        s.blockWhenKeyguard = false;
        s.blockWhenPanelExpanded = false;
        s.blockWhenScreenOff = false;
        s.blockWhenInCall = false;
        s.skipOngoing = false;
        s.skipForegroundService = false;
        s.skipMedia = true;
        s.skipLowImportance = true;

        s.engineEnabled = false;
        s.globalCooldownMs = 111;
        s.keyCooldownMs = 222;
        s.appCooldownMs = 333;
        s.burstLimit = 5;
        s.burstWindowMs = 4444;
        s.minuteLimit = 6;
        s.userDismissCooldownMs = 7777;
        s.userDismissScope = Settings.SCOPE_GLOBAL;

        s.ledgerEnabled = false;
        s.dedupSameContent = false;
        s.pauseUntilWallMs = 123456789012L;

        s.ignoredEntries.clear();
        s.ignoredEntries.add(new Settings.IgnoredEntry("0|pkg|1|tag|0", "pkg", "标题", 998877665544L));
        s.ignoredEntries.add(new Settings.IgnoredEntry("0|pkg2|2|tag|0", "pkg2", "", 112233445566L));

        s.debugLog = true;
        return s;
    }

    private static void assertSettingsEqual(Settings expected, Settings actual) {
        assertEquals(expected.enabled, actual.enabled);
        assertEquals(expected.styleId, actual.styleId);
        assertEquals(expected.customStylesJson, actual.customStylesJson);
        assertEquals(expected.themeMode, actual.themeMode);

        assertEquals(expected.enterAnim, actual.enterAnim);
        assertEquals(expected.exitAnim, actual.exitAnim);
        assertEquals(expected.enterDir, actual.enterDir);
        assertEquals(expected.enterDurationMs, actual.enterDurationMs);
        assertEquals(expected.enterOvershoot, actual.enterOvershoot, 0.001f);
        assertEquals(expected.exitDurationMs, actual.exitDurationMs);
        assertEquals(expected.staggerContent, actual.staggerContent);

        assertEquals(expected.gestureHorizontal, actual.gestureHorizontal);
        assertEquals(expected.gestureVertical, actual.gestureVertical);
        assertEquals(expected.gesturePullDown, actual.gesturePullDown);
        assertEquals(expected.gestureTap, actual.gestureTap);
        assertEquals(expected.gestureLongPress, actual.gestureLongPress);
        assertEquals(expected.swipeThresholdPx, actual.swipeThresholdPx, 0.001f);
        assertEquals(expected.flingVelocityPxS, actual.flingVelocityPxS, 0.001f);

        assertEquals(expected.anchor, actual.anchor);
        assertEquals(expected.offsetXDp, actual.offsetXDp);
        assertEquals(expected.offsetYDp, actual.offsetYDp);
        assertEquals(expected.widthScale, actual.widthScale, 0.001f);
        assertEquals(expected.cornerScale, actual.cornerScale, 0.001f);
        assertEquals(expected.bgAlpha, actual.bgAlpha, 0.001f);

        assertEquals(expected.lensEnabled, actual.lensEnabled);
        assertEquals(expected.lensThickness, actual.lensThickness, 0.001f);
        assertEquals(expected.lensEdge, actual.lensEdge, 0.001f);
        assertEquals(expected.lensDispersion, actual.lensDispersion, 0.001f);
        assertEquals(expected.lensSpecular, actual.lensSpecular, 0.001f);
        assertEquals(expected.lensIOR, actual.lensIOR, 0.001f);
        assertEquals(expected.lensDynamic, actual.lensDynamic);
        assertEquals(expected.lensDynamicStrength, actual.lensDynamicStrength, 0.001f);
        assertEquals(expected.lensAdaptiveShadow, actual.lensAdaptiveShadow, 0.001f);
        assertEquals(expected.lensAccentFollow, actual.lensAccentFollow);

        assertEquals(expected.autoDismissMs, actual.autoDismissMs);
        assertEquals(expected.showAppName, actual.showAppName);
        assertEquals(expected.maxTextLines, actual.maxTextLines);

        assertEquals(expected.filterMode, actual.filterMode);
        assertEquals(expected.blockedApps, actual.blockedApps);
        assertEquals(expected.allowedApps, actual.allowedApps);
        assertEquals(expected.foregroundSuppressApps, actual.foregroundSuppressApps);

        assertEquals(expected.blockWhenDnd, actual.blockWhenDnd);
        assertEquals(expected.blockWhenKeyguard, actual.blockWhenKeyguard);
        assertEquals(expected.blockWhenPanelExpanded, actual.blockWhenPanelExpanded);
        assertEquals(expected.blockWhenScreenOff, actual.blockWhenScreenOff);
        assertEquals(expected.blockWhenInCall, actual.blockWhenInCall);
        assertEquals(expected.skipOngoing, actual.skipOngoing);
        assertEquals(expected.skipForegroundService, actual.skipForegroundService);
        assertEquals(expected.skipMedia, actual.skipMedia);
        assertEquals(expected.skipLowImportance, actual.skipLowImportance);

        assertEquals(expected.engineEnabled, actual.engineEnabled);
        assertEquals(expected.globalCooldownMs, actual.globalCooldownMs);
        assertEquals(expected.keyCooldownMs, actual.keyCooldownMs);
        assertEquals(expected.appCooldownMs, actual.appCooldownMs);
        assertEquals(expected.burstLimit, actual.burstLimit);
        assertEquals(expected.burstWindowMs, actual.burstWindowMs);
        assertEquals(expected.minuteLimit, actual.minuteLimit);
        assertEquals(expected.userDismissCooldownMs, actual.userDismissCooldownMs);
        assertEquals(expected.userDismissScope, actual.userDismissScope);

        assertEquals(expected.ledgerEnabled, actual.ledgerEnabled);
        assertEquals(expected.dedupSameContent, actual.dedupSameContent);
        assertEquals(expected.pauseUntilWallMs, actual.pauseUntilWallMs);

        assertEquals(expected.ignoredEntries.size(), actual.ignoredEntries.size());
        for (int i = 0; i < expected.ignoredEntries.size(); i++) {
            Settings.IgnoredEntry e = expected.ignoredEntries.get(i);
            Settings.IgnoredEntry a = actual.ignoredEntries.get(i);
            assertEquals(e.key, a.key);
            assertEquals(e.pkg, a.pkg);
            assertEquals(e.title, a.title);
            assertEquals(e.atWallMs, a.atWallMs);
        }

        assertEquals(expected.debugLog, actual.debugLog);
    }

    @Test
    public void roundTrip_preservesEveryField() {
        Settings s = sample();
        String json = s.toJson();
        assertNotNull(json);
        Settings back = Settings.fromJson(json);
        assertNotNull("fromJson 不应返回 null", back);
        assertSettingsEqual(s, back);
    }

    @Test
    public void roundTrip_defaults() {
        // 全新默认设置也要能完整往返（广播携带的可能是用户从未改过的默认值）
        Settings back = Settings.fromJson(new Settings().toJson());
        assertNotNull(back);
        assertSettingsEqual(new Settings(), back);
    }

    @Test
    public void fromJson_rejectsGarbage() {
        assertNull(Settings.fromJson((String) null));
        assertNull(Settings.fromJson(""));
        assertNull(Settings.fromJson("不是 JSON"));
        assertNull(Settings.fromJson("[1,2,3]")); // 数组而非对象
    }

    @Test
    public void fromJson_missingKeys_fallBackToDefaults() {
        Settings back = Settings.fromJson("{\"hulfix_anchor\": 6}");
        assertNotNull(back);
        assertEquals(Settings.ANCHOR_BOTTOM_LEFT, back.anchor);
        // 未提供的键保持默认值
        assertEquals(new Settings().styleId, back.styleId);
        assertEquals(new Settings().autoDismissMs, back.autoDismissMs);
        assertTrue(back.blockedApps.isEmpty());
    }

    @Test
    public void roundTrip_isStable() {
        // 二次序列化结果一致（广播内容稳定，hook 端重复应用不会抖动）
        String once = sample().toJson();
        String twice = Settings.fromJson(once).toJson();
        assertEquals(once, twice);
    }
}
