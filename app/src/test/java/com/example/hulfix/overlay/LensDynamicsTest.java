package com.example.hulfix.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link LensDynamics} 实时演算测试：纯 JVM，覆盖逐帧推进的全部数值纪律。
 */
public class LensDynamicsTest {

    private static final float EPS = 0.0001f;

    /** 推进 n 帧（60fps 步长）。 */
    private static void run(LensDynamics d, int frames) {
        for (int i = 0; i < frames; i++) d.step(1f / 60f);
    }

    @Test
    public void resetZeroesInteractionState() {
        LensDynamics d = new LensDynamics();
        d.setConfig(1f, 0.35f);
        d.setBackdropLuma(0.8f);
        d.pulseScene(1f);
        d.setTouch(0.9f, -0.9f, true);
        run(d, 30);
        d.reset();
        // 交互状态归零：光源回默认方向、相位归零、入场未开始（玻璃保持暗态）
        assertEquals(LensUniforms.DEFAULT_LIGHT_X, d.lightX(), EPS);
        assertEquals(LensUniforms.DEFAULT_LIGHT_Y, d.lightY(), EPS);
        assertEquals(LensUniforms.DEFAULT_FLOW_PHASE, d.flowPhase(), EPS);
        assertTrue("entry=0 after reset should be dark: " + d.ambient(), d.ambient() < 0.05f);
        // 阴影保留 25% 入场地板（卡片飞入时也有一丝接触阴影），但显著弱于到位后
        assertTrue("shadow should be faint before entry: " + d.shadow(), d.shadow() < 0.1f);
        assertTrue("reset marks entry done (no ramp until beginEntrance)", d.entryDone());
    }

    @Test
    public void strengthZeroFreezesStaticLook() {
        LensDynamics d = new LensDynamics();
        d.setConfig(0f, 0.35f);
        d.beginEntrance(300f);
        d.setBackdropLuma(1f);
        d.pulseScene(1f);
        d.setTouch(1f, 1f, true);
        run(d, 120);
        // strength=0：无论输入如何剧烈，输出恒为静态设计值
        assertEquals(LensUniforms.DEFAULT_LIGHT_X, d.lightX(), EPS);
        assertEquals(LensUniforms.DEFAULT_LIGHT_Y, d.lightY(), EPS);
        assertEquals(LensUniforms.DEFAULT_AMBIENT, d.ambient(), EPS);
        assertEquals(LensUniforms.DEFAULT_SHADOW, d.shadow(), EPS);
        assertEquals(LensUniforms.DEFAULT_FLOW_PHASE, d.flowPhase(), EPS);
    }

    @Test
    public void entranceRampsAmbientFromDark() {
        LensDynamics d = new LensDynamics();
        d.setConfig(1f, 0.35f);
        d.setBackdropLuma(0.5f);
        d.beginEntrance(300f);
        // 第一帧：入场进度≈0 → 环境光接近 0（卡片未飞入，玻璃尚未点亮）
        d.step(1f / 60f);
        float early = d.ambient();
        assertTrue("entrance start should be dark, got " + early, early < 0.05f);
        // 跑完入场：环境光回到设计基准附近（0.26 + 0.48*0.5 = 0.5）
        run(d, 60);
        assertTrue("entrance should be done", d.entryDone());
        float settled = d.ambient();
        assertEquals(0.5f, settled, 0.02f);
    }

    @Test
    public void sceneEnergyBurstsThenDecays() {
        LensDynamics d = new LensDynamics();
        d.setConfig(1f, 0.35f);
        d.setBackdropLuma(0.5f);
        d.beginEntrance(300f);
        run(d, 60); // 入场到位
        float idle = d.ambient();
        d.pulseScene(1f);
        run(d, 10);
        float burst = d.ambient();
        assertTrue("pop should brighten: " + burst + " vs " + idle, burst > idle + 0.2f);
        // 能量衰减后回到空闲值
        run(d, 180);
        assertEquals(idle, d.ambient(), 0.02f);
    }

    @Test
    public void touchLightMovesHighlightDirection() {
        LensDynamics d = new LensDynamics();
        d.setConfig(1f, 0.35f);
        d.beginEntrance(300f);
        run(d, 60);
        // 手指按在右侧：光源应趋近 +1
        d.setTouch(1f, 0f, true);
        run(d, 60);
        assertTrue("light should follow touch right: " + d.lightX(), d.lightX() > 0.6f);
        // 抬手：平滑游弋回默认方向（不回跳）
        d.setTouch(0f, 0f, false);
        run(d, 120);
        assertTrue("light should drift back: " + d.lightX(), d.lightX() < 0.6f);
    }

    @Test
    public void brightBackdropRaisesAmbient() {
        LensDynamics dark = new LensDynamics();
        dark.setConfig(1f, 0.35f);
        dark.setBackdropLuma(0f);
        dark.beginEntrance(300f);
        run(dark, 60);

        LensDynamics bright = new LensDynamics();
        bright.setConfig(1f, 0.35f);
        bright.setBackdropLuma(1f);
        bright.beginEntrance(300f);
        run(bright, 60);

        assertTrue("bright backdrop should be brighter: " + bright.ambient(),
                bright.ambient() > dark.ambient() + 0.3f);
    }

    @Test
    public void shadowTracksAdaptiveSetting() {
        LensDynamics weak = new LensDynamics();
        weak.setConfig(1f, 0.05f);
        weak.setBackdropLuma(0.5f);
        weak.beginEntrance(300f);
        run(weak, 60);

        LensDynamics strong = new LensDynamics();
        strong.setConfig(1f, 0.9f);
        strong.setBackdropLuma(0.5f);
        strong.beginEntrance(300f);
        run(strong, 60);

        assertTrue(strong.shadow() > weak.shadow() + 0.2f);
    }

    @Test
    public void flowPhaseAdvancesAndWraps() {
        LensDynamics d = new LensDynamics();
        d.setConfig(1f, 0.35f);
        d.beginEntrance(300f);
        run(d, 5);
        float p1 = d.flowPhase();
        assertTrue("phase should advance", p1 > 0f);
        // 长跑后仍在 [0, 2π) 内
        run(d, 60 * 30);
        float p2 = d.flowPhase();
        assertTrue("phase in range: " + p2, p2 >= 0f && p2 < LensUniforms.TWO_PI);
    }

    @Test
    public void swipeEnergyAcceleratesFlow() {
        LensDynamics idleD = new LensDynamics();
        idleD.setConfig(1f, 0.35f);
        idleD.beginEntrance(300f);
        run(idleD, 60);
        float before = idleD.flowPhase();
        run(idleD, 30);
        float idleDelta = idleD.flowPhase() - before;

        LensDynamics swipeD = new LensDynamics();
        swipeD.setConfig(1f, 0.35f);
        swipeD.beginEntrance(300f);
        run(swipeD, 60);
        swipeD.addSwipe(1f);
        float before2 = swipeD.flowPhase();
        run(swipeD, 30);
        float swipeDelta = swipeD.flowPhase() - before2;

        assertTrue("swipe should speed up flow: " + swipeDelta + " vs " + idleDelta,
                swipeDelta > idleDelta * 1.5f);
    }

    @Test
    public void nanAndHugeDtAreSafe() {
        LensDynamics d = new LensDynamics();
        d.setConfig(Float.NaN, Float.NaN);
        d.beginEntrance(Float.NaN);
        d.step(Float.NaN);
        d.step(-5f);
        d.step(1e9f);
        // 所有输出必须落在合法区间
        assertTrue(d.ambient() >= 0f && d.ambient() <= 1f);
        assertTrue(d.shadow() >= 0f && d.shadow() <= 1f);
        assertTrue(d.lightX() >= -1f && d.lightX() <= 1f);
        assertTrue(d.lightY() >= -1f && d.lightY() <= 1f);
        assertTrue(d.flowPhase() >= 0f && d.flowPhase() < LensUniforms.TWO_PI);
    }

    @Test
    public void pressFeedbackBrightensGlass() {
        LensDynamics d = new LensDynamics();
        d.setConfig(1f, 0.35f);
        d.setBackdropLuma(0.5f);
        d.beginEntrance(300f);
        run(d, 60);
        float idle = d.ambient();
        d.setPress(1f);
        run(d, 5);
        assertTrue(d.ambient() > idle + 0.1f);
        d.setPress(0f);
        run(d, 60);
        assertEquals(idle, d.ambient(), 0.02f);
    }
}
