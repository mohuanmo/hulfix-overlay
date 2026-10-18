package com.example.hulfix.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.example.hulfix.core.MessageLedger.MessageRecord;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

/**
 * 消息台账单元测试：去重依据、统计、LRU 淘汰、序列化。
 */
public class MessageLedgerTest {

    private MessageLedger ledger;

    @Before
    public void setUp() {
        ledger = new MessageLedger();
    }

    @Test
    public void touchCreatesRecord() {
        MessageRecord r = ledger.touch("k1", "com.a", "标题A");
        assertNotNull(r);
        assertEquals(1, r.seenCount);
        assertEquals("k1", ledger.get("k1").key);
    }

    @Test
    public void shownAndSuppressedCounters() {
        MessageRecord r = ledger.touch("k1", "com.a", "标题A");
        ledger.recordShown(r, "h1", 100, 1000);
        assertEquals(1, r.showCount);
        assertEquals("h1", r.lastContentHash);
        ledger.recordSuppressed(r, CooldownEngine.R_KEY_COOLDOWN, 200);
        assertEquals(1, r.suppressedCount);
        ledger.recordShown(r, "h2", 300, 2000);
        assertEquals(2, r.showCount);
        assertEquals(0, r.suppressedCount); // 展示成功后重置
    }

    @Test
    public void userDismissedFlag() {
        MessageRecord r = ledger.touch("k1", "com.a", "标题A");
        assertFalse(r.wasUserDismissed());
        ledger.recordUserDismissed(r, 500, 5000);
        assertTrue(r.wasUserDismissed());
    }

    @Test
    public void snapshotRecentSortedByLastEvent() {
        ledger.touch("k1", "com.a", "A");
        ledger.touch("k2", "com.a", "B");
        ledger.touch("k3", "com.a", "C");
        MessageRecord r1 = ledger.get("k1");
        MessageRecord r2 = ledger.get("k2");
        MessageRecord r3 = ledger.get("k3");
        r1.lastEventElapsedMs = 100;
        r2.lastEventElapsedMs = 300;
        r3.lastEventElapsedMs = 200;

        List<MessageRecord> snap = ledger.snapshotRecent(10);
        assertEquals(3, snap.size());
        assertEquals("k2", snap.get(0).key);
        assertEquals("k3", snap.get(1).key);
        assertEquals("k1", snap.get(2).key);
    }

    @Test
    public void lruEviction() {
        for (int i = 0; i < MessageLedger.CAPACITY + 20; i++) {
            ledger.touch("k" + i, "com.a", "t" + i);
            ledger.get("k" + i).lastEventElapsedMs = i; // 越大越新
        }
        assertEquals(MessageLedger.CAPACITY, ledger.size());
        // 最早的应被淘汰
        assertNotNull(ledger.get("k" + (MessageLedger.CAPACITY + 19)));
    }

    @Test
    public void toJsonRoundTrip() {
        ledger.touch("k1", "com.a", "标题A");
        ledger.recordShown(ledger.get("k1"), "h1", 100, 1000);
        ledger.touch("k2", "com.b", "标题B");
        ledger.recordSuppressed(ledger.get("k2"), CooldownEngine.R_DND, 200);

        String json = ledger.toJson(10);
        assertTrue(json.contains("k1"));
        assertTrue(json.contains("标题B"));
        assertTrue(json.contains("\"reason\""));
    }

    @Test
    public void clear() {
        ledger.touch("k1", "com.a", "A");
        ledger.clear();
        assertEquals(0, ledger.size());
    }
}
