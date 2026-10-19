package com.example.hulfix.overlay;

/**
 * 动态光效引擎的逐帧演算核心（<b>纯 JVM</b>，无任何 Android 依赖，可完整单测）。
 *
 * <p>{@link LensBackdropView} 只负责 I/O（Choreographer 帧回调、uniform 推送、
 * 背景内容亮度采样）；本类负责"每一帧光效应该长什么样"的全部数学：</p>
 *
 * <ul>
 *   <li><b>光源方向</b>：触摸时跟随手指（归一化 -1..1），空闲时围绕默认方向缓慢游弋；
 *       通过指数趋近平滑过渡，绝不跳变；</li>
 *   <li><b>环境光</b>：以玻璃后方内容的平均亮度为基准（亮底 → 玻璃更亮），
 *       叠加入场进度、按压反馈、消息弹出能量与滑动能量；</li>
 *   <li><b>自适应阴影</b>：随环境光增强而衰减（强光下阴影更浅），并随入场渐显；</li>
 *   <li><b>流动相位</b>：持续推进，交互能量注入时加速——消息弹出/滑动时
 *       光泽带明显加速流动，能量衰减后恢复慵懒速度；</li>
 *   <li><b>强度混合</b>：所有输出按 strength 在"静态设计值"与"动态计算值"之间
 *       线性混合，strength=0 时逐位回到静态观感（旧版默认外观）。</li>
 * </ul>
 *
 * <p>数值纪律：所有输入先钳制再进入计算（NaN 一律回落下限），相位对 2π 取模，
 *  dt 上限 100ms（卡帧保护，防止长时间后台后相位暴走）。</p>
 */
public final class LensDynamics {

    // ==================== 静态设计值（strength=0 时的观感，见 LensUniforms） ====================
    private static final float DEF_LIGHT_X = LensUniforms.DEFAULT_LIGHT_X;
    private static final float DEF_LIGHT_Y = LensUniforms.DEFAULT_LIGHT_Y;
    private static final float DEF_AMBIENT = LensUniforms.DEFAULT_AMBIENT;
    private static final float DEF_SHADOW = LensUniforms.DEFAULT_SHADOW;
    private static final float DEF_FLOW = LensUniforms.DEFAULT_FLOW_PHASE;

    /** 单帧最大积分步长（秒）：卡帧 / 后台恢复时不暴走。 */
    private static final float MAX_DT = 0.1f;
    /** 光源趋近速率（1/s）：越大光源越"紧跟"手指。 */
    private static final float LIGHT_LERP_RATE = 7f;
    /** 能量衰减速率（1/s）。 */
    private static final float SCENE_DECAY = 2.4f;
    private static final float SWIPE_DECAY = 3.2f;
    /** 空闲游弋幅度（围绕默认方向的正弦摆动）。 */
    private static final float DRIFT_X = 0.18f;
    private static final float DRIFT_Y = 0.12f;
    /** 空闲流动基速（rad/s）与交互加速系数。 */
    private static final float FLOW_BASE_SPEED = 0.9f;
    private static final float FLOW_SCENE_GAIN = 3.0f;
    private static final float FLOW_SWIPE_GAIN = 1.8f;

    // ==================== 配置（由设置页驱动） ====================
    private float mStrength = 1f;
    private float mAdaptiveShadow = 0.35f;

    // ==================== 交互输入（由控制器 / 手势写入） ====================
    private float mTouchX;
    private float mTouchY;
    private boolean mTouchActive;
    private float mPress;
    private float mEntry;
    private float mSceneEnergy;
    private float mSwipeEnergy;
    /** 玻璃后方内容平均亮度 0..1（由 LensBackdropView 采样后写入）。 */
    private float mBackdropLuma = 0.5f;

    // ==================== 内部状态 ====================
    private float mLightX = DEF_LIGHT_X;
    private float mLightY = DEF_LIGHT_Y;
    private float mFlowPhase;
    private boolean mEntryDone = true;
    private float mEntryDurationMs = 320f;

    // ==================== 配置 ====================

    public void setConfig(float strength, float adaptiveShadow) {
        mStrength = clamp01(strength);
        mAdaptiveShadow = clamp01(adaptiveShadow);
    }

    public float strength() {
        return mStrength;
    }

    public float adaptiveShadow() {
        return mAdaptiveShadow;
    }

    // ==================== 输入 ====================

    /** 触摸光源位置（归一化 -1..1，相对卡片中心；y 向下为正）。 */
    public void setTouch(float x, float y, boolean active) {
        mTouchX = active ? clamp(x, -1f, 1f) : 0f;
        mTouchY = active ? clamp(y, -1f, 1f) : 0f;
        mTouchActive = active;
    }

    /** 按压反馈 0..1。 */
    public void setPress(float p) {
        mPress = clamp01(p);
    }

    /** 入场进度 0..1（卡片飞入过程的完成度）。 */
    public void setEntry(float e) {
        mEntry = clamp01(e);
    }

    /** 消息弹出能量脉冲（叠加后衰减，用于触发一次高光爆发）。 */
    public void pulseScene(float amount) {
        mSceneEnergy = clamp01(mSceneEnergy + clamp01(amount));
    }

    /** 滑动能量（叠加后衰减，拖动时光泽加速）。 */
    public void addSwipe(float amount) {
        mSwipeEnergy = clamp01(mSwipeEnergy + clamp01(amount));
    }

    /**
     * 开始入场：复位全部交互状态，环境光/阴影从 0 按 durationMs 渐亮。
     *
     * <p>控制器在播放入场动画时调用一次即可，ramp 由 {@link #step} 内部推进
     * （与卡片飞入节奏对齐）。</p>
     */
    public void beginEntrance(float durationMs) {
        reset();
        mEntryDurationMs = Math.max(160f, durationMs);
        mEntryDone = false;
    }

    /** 入场 ramp 是否已到位。 */
    public boolean entryDone() {
        return mEntryDone;
    }

    /** 玻璃后方内容平均亮度 0..1（暗底 → 玻璃更沉稳；亮底 → 高光更锐）。 */
    public void setBackdropLuma(float luma) {
        mBackdropLuma = clamp01(luma);
    }

    /** 复位到静态初态（视图池复用时调用）。 */
    public void reset() {
        mTouchX = 0f;
        mTouchY = 0f;
        mTouchActive = false;
        mPress = 0f;
        mEntry = 0f;
        mSceneEnergy = 0f;
        mSwipeEnergy = 0f;
        mLightX = DEF_LIGHT_X;
        mLightY = DEF_LIGHT_Y;
        mFlowPhase = 0f;
        mEntryDone = true;
    }

    // ==================== 逐帧演算 ====================

    /**
     * 推进一帧。
     *
     * @param dt 距上一帧的秒数（调用方负责钳制；本方法内部仍设上限保护）
     */
    public void step(float dt) {
        float d = clamp(dt, 0f, MAX_DT);

        // 0) 入场进度 ramp：环境光/阴影随卡片飞入渐亮
        if (!mEntryDone) {
            mEntry = clamp01(mEntry + d * 1000f / mEntryDurationMs);
            if (mEntry >= 1f) {
                mEntry = 1f;
                mEntryDone = true;
            }
        }

        // 1) 能量衰减（消息弹出 / 滑动）
        mSceneEnergy = approach(mSceneEnergy, 0f, d * SCENE_DECAY);
        mSwipeEnergy = approach(mSwipeEnergy, 0f, d * SWIPE_DECAY);

        // 2) 流动相位：基速 + 交互加速；对 2π 取模防精度漂移
        float speed = FLOW_BASE_SPEED + FLOW_SCENE_GAIN * mSceneEnergy
                + FLOW_SWIPE_GAIN * mSwipeEnergy;
        mFlowPhase += d * speed;
        if (mFlowPhase >= LensUniforms.TWO_PI || mFlowPhase < 0f) {
            mFlowPhase -= LensUniforms.TWO_PI * (float) Math.floor(mFlowPhase / LensUniforms.TWO_PI);
        }

        // 3) 光源目标：触摸优先；空闲时围绕默认方向缓慢游弋（正弦，相位与流动联动）
        float tx;
        float ty;
        if (mTouchActive) {
            tx = mTouchX;
            ty = mTouchY;
        } else {
            tx = DEF_LIGHT_X + DRIFT_X * (float) Math.sin(mFlowPhase * 0.45);
            ty = DEF_LIGHT_Y + DRIFT_Y * (float) Math.cos(mFlowPhase * 0.32);
        }
        // 指数趋近：帧率无关的平滑（120Hz 与 60Hz 观感一致）
        float k = 1f - (float) Math.exp(-d * LIGHT_LERP_RATE);
        mLightX += (tx - mLightX) * k;
        mLightY += (ty - mLightY) * k;
    }

    // ==================== 输出（已按 strength 混合，可直接推送 uniform） ====================

    public float lightX() {
        return mix(DEF_LIGHT_X, mLightX, mStrength);
    }

    public float lightY() {
        return mix(DEF_LIGHT_Y, mLightY, mStrength);
    }

    public float ambient() {
        float dynamic = ambientRaw();
        return mix(DEF_AMBIENT, dynamic, mStrength);
    }

    public float shadow() {
        float dynamic = mAdaptiveShadow * (0.55f + 0.45f * (1f - ambientRaw()))
                * (0.25f + 0.75f * mEntry);
        return mix(DEF_SHADOW, dynamic, mStrength);
    }

    public float flowPhase() {
        return mix(DEF_FLOW, mFlowPhase, mStrength);
    }

    /** 动态环境光原始值（未做 strength 混合）：入场渐亮 + 背光亮度 + 交互能量。 */
    private float ambientRaw() {
        float v = mEntry * (0.26f + 0.48f * mBackdropLuma
                + 0.30f * mPress
                + 0.42f * mSceneEnergy
                + 0.30f * mSwipeEnergy);
        return clamp01(v);
    }

    // ==================== 数值工具 ====================

    private static float mix(float a, float b, float t) {
        return a + (b - a) * clamp01(t);
    }

    /** 指数趋近目标（帧率无关）。 */
    private static float approach(float v, float target, float maxDelta) {
        if (v < target) return Math.min(target, v + maxDelta);
        if (v > target) return Math.max(target, v - maxDelta);
        return target;
    }

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    private static float clamp(float v, float lo, float hi) {
        if (Float.isNaN(v)) return lo;
        if (v < lo) return lo;
        if (v > hi) return hi;
        return v;
    }
}
