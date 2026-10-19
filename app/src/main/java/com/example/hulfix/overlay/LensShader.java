package com.example.hulfix.overlay;

/**
 * AGSL（Android Graphics Shading Language）真折射着色器源码与 uniform 契约。
 *
 * <p>本类为<b>纯 JVM</b>（无任何 Android framework 依赖），因此可以在单元测试中直接校验
 * 源码结构与 uniform 声明一致性。真正的编译发生在 {@link LensEngineImpl} 中
 * （{@code new RuntimeShader(source())}，API 33+）。</p>
 *
 * <p><b>物理模型</b>（与"画出来的假玻璃"的本质区别）：</p>
 * <ol>
 *   <li>用圆角矩形 SDF 表达玻璃面板轮廓，得到有符号距离场 d（内部为负、外部为正，IQ 约定）；</li>
 *   <li>对 SDF 做中心差分求解析梯度 → 外法线 n（面板中心处法线垂直屏幕，无偏折；
 *       靠近圆角边缘处法线逐渐倾斜，形成"边缘透镜"）；</li>
 *   <li>Snell 定律：{@code refract(I, n, 1/IOR)} 计算光线进入玻璃后的偏折方向，
 *       按偏折方向偏移采样背景纹理——背景像素真实发生位移；</li>
 *   <li>色散：R/G/B 三通道使用略微不同的折射率（±3%），边缘出现真实彩虹色散；</li>
 *   <li>菲涅尔项：掠射角处镜面反射自然增强，得到"边缘发光"；</li>
 *   <li>厚度环 + 相位呼吸微光：暗示玻璃边缘更厚的物理事实。</li>
 *   <li><b>动态光效层</b>（实时渲染引擎逐帧驱动，见 {@link LensBackdropView}）：
 *       方向性镜面高光（半角向量 H = normalize(L+V)，光源随交互滑动）、
 *       自适应接触阴影（背离光源侧变暗，边缘最明显）、流动光泽（相位持续推进）。</li>
 * </ol>
 *
 * <p>SkSL 语法约束（与 GLSL 的差异已规避）：无 {@code texture2D/varying/attribute}；
 * 子着色器采样用 {@code uBackdrop.eval(pixelCoord)}；入口签名为
 * {@code half4 main(float2 fragCoord)}（RenderEffect 规范）；不使用 dFdx/dFdy，
 * 法线完全由 SDF 解析求得，兼容性最好。</p>
 */
public final class LensShader {

    private LensShader() {
    }

    // ==================== uniform 名（Java 侧与 shader 侧的唯一定义源） ====================

    public static final String U_RESOLUTION = "uResolution";     // float2：视图像素尺寸
    public static final String U_RADIUS_PX = "uRadiusPx";       // float：圆角半径（px）
    public static final String U_EDGE_WIDTH_PX = "uEdgeWidthPx"; // float：透镜过渡带宽度（px）
    public static final String U_THICKNESS = "uThickness";       // float：折射位移倍率（px）
    public static final String U_EDGE_BOOST = "uEdgeBoost";     // float：边缘透镜增幅 0..1
    public static final String U_DISPERSION = "uDispersion";     // float：色散强度 0..1
    public static final String U_SPECULAR = "uSpecular";         // float：镜面高光 0..2
    public static final String U_IOR = "uIOR";                   // float：折射率 1.01..2
    public static final String U_HIGHLIGHT = "uHighlight";       // float3：高光色 RGB 0..1
    public static final String U_PHASE = "uPhase";               // float：呼吸相位 0..2π
    public static final String U_BACKDROP = "uBackdrop";         // shader：RenderEffect 注入的背景纹理

    // ---------- 动态光效 uniform（实时渲染引擎逐帧驱动） ----------
    public static final String U_LIGHT_DIR = "uLightDir";       // float2：光源方向（屏幕空间，已归一化）
    public static final String U_AMBIENT = "uAmbient";           // float：环境光强度 0..1
    public static final String U_SHADOW = "uShadow";             // float：自适应阴影强度 0..1
    public static final String U_FLOW = "uFlow";                 // float：光泽流动相位 0..2π

    /** 色散时 R/B 通道相对 G 通道的折射率微偏量（±3%，物理上接近真实玻璃的阿贝数下限）。 */
    public static final float DISPERSION_SPREAD = 0.03f;

    /** 上述值的源码字面量（避免在字符串拼接中依赖浮点格式化）。 */
    private static final String DISPERSION_LITERAL = "0.03";

    // ==================== 源码 ====================

    public static final String SOURCE = ""
            + "uniform float2 " + U_RESOLUTION + ";\n"
            + "uniform float " + U_RADIUS_PX + ";\n"
            + "uniform float " + U_EDGE_WIDTH_PX + ";\n"
            + "uniform float " + U_THICKNESS + ";\n"
            + "uniform float " + U_EDGE_BOOST + ";\n"
            + "uniform float " + U_DISPERSION + ";\n"
            + "uniform float " + U_SPECULAR + ";\n"
            + "uniform float " + U_IOR + ";\n"
            + "uniform float3 " + U_HIGHLIGHT + ";\n"
            + "uniform float " + U_PHASE + ";\n"
            + "uniform shader " + U_BACKDROP + ";\n"
            + "\n"
            + "// ---------- 动态光效 uniform（实时渲染引擎逐帧驱动） ----------\n"
            + "uniform float2 " + U_LIGHT_DIR + ";\n"
            + "uniform float " + U_AMBIENT + ";\n"
            + "uniform float " + U_SHADOW + ";\n"
            + "uniform float " + U_FLOW + ";\n"
            + "\n"
            + "// 圆角矩形有符号距离场：内部为负、边缘为 0、外部为正（IQ 约定）\n"
            + "float sdRoundedBox(float2 p, float2 b, float r) {\n"
            + "    float2 q = abs(p) - b + r;\n"
            + "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 fragCoord) {\n"
            + "    float2 centered = fragCoord - 0.5 * " + U_RESOLUTION + ";\n"
            + "    float2 halfSize = 0.5 * " + U_RESOLUTION + ";\n"
            + "    float d = sdRoundedBox(centered, halfSize, " + U_RADIUS_PX + ");\n"
            + "    if (d > 0.0) {\n"
            + "        return half4(0.0, 0.0, 0.0, 0.0);\n"
            + "    }\n"
            + "\n"
            + "    // 解析法线：对 SDF 做中心差分求屏幕空间梯度（不依赖任何屏幕导数扩展，兼容性最好）\n"
            + "    float e = 1.0;\n"
            + "    float dx = sdRoundedBox(centered + float2(e, 0.0), halfSize, " + U_RADIUS_PX + ")\n"
            + "             - sdRoundedBox(centered - float2(e, 0.0), halfSize, " + U_RADIUS_PX + ");\n"
            + "    float dy = sdRoundedBox(centered + float2(0.0, e), halfSize, " + U_RADIUS_PX + ")\n"
            + "             - sdRoundedBox(centered - float2(0.0, e), halfSize, " + U_RADIUS_PX + ");\n"
            + "    float2 grad = float2(dx, dy);\n"
            + "    float gl = length(grad);\n"
            + "    // d 内部为负、外部为正 → 梯度天然指向外侧，即外法线\n"
            + "    float2 outward = gl > 0.0001 ? grad / gl : float2(0.0, 0.0);\n"
            + "\n"
            + "    // 透镜厚度场：距边缘越近法线倾斜越强，模拟玻璃边缘更厚的透镜效应\n"
            + "    // depth=0 在边缘，1 在玻璃内部（IQ 约定 d<=0）\n"
            + "    float depth = clamp(-d / max(" + U_EDGE_WIDTH_PX + ", 1.0), 0.0, 1.0);\n"
            + "    float tilt = (1.0 - depth) * " + U_EDGE_BOOST + ";\n"
            + "    float3 n = normalize(float3(outward * tilt, 1.0));\n"
            + "\n"
            + "    // Snell 定律：入射光垂直屏幕（-z），eta = 空气/玻璃\n"
            + "    float3 incident = float3(0.0, 0.0, -1.0);\n"
            + "    float eta = 1.0 / max(" + U_IOR + ", 1.01);\n"
            + "    float3 rR = refract(incident, n, eta * (1.0 + " + U_DISPERSION + " * " + DISPERSION_LITERAL + "));\n"
            + "    float3 rG = refract(incident, n, eta);\n"
            + "    float3 rB = refract(incident, n, eta * (1.0 - " + U_DISPERSION + " * " + DISPERSION_LITERAL + "));\n"
            + "\n"
            + "    // 全反射保护：refract 返回零向量时退回原方向\n"
            + "    rR = length(rR) < 0.001 ? incident : rR;\n"
            + "    rG = length(rG) < 0.001 ? incident : rG;\n"
            + "    rB = length(rB) < 0.001 ? incident : rB;\n"
            + "\n"
            + "    // 按偏折方向偏移采样背景：背景像素真实位移（真折射的核心）\n"
            + "    half4 sr = " + U_BACKDROP + ".eval(fragCoord + rR.xy * " + U_THICKNESS + ");\n"
            + "    half4 sg = " + U_BACKDROP + ".eval(fragCoord + rG.xy * " + U_THICKNESS + ");\n"
            + "    half4 sb = " + U_BACKDROP + ".eval(fragCoord + rB.xy * " + U_THICKNESS + ");\n"
            + "    half3 refracted = half3(sr.r, sg.g, sb.b);\n"
            + "    half alpha = max(max(sr.a, sg.a), sb.a);\n"
            + "    half4 col = half4(refracted, alpha);\n"
            + "\n"
            + "    // Schlick 菲涅尔：F0 基础反射率（玻璃约 4%）+ 掠射角增强（真玻璃边缘发光的物理来源）\n"
            + "    float f0 = pow((1.0 - " + U_IOR + ") / (1.0 + " + U_IOR + "), 2.0);\n"
            + "    float fresnel = f0 + (1.0 - f0) * pow(1.0 - clamp(n.z, 0.0, 1.0), 5.0);\n"
            + "    col.rgb += " + U_SPECULAR + " * fresnel * " + U_HIGHLIGHT + ";\n"
            + "\n"
            + "    // 边缘厚度环：玻璃边缘更厚 → 一圈持续的亮边（d=0 处最亮，向内衰减）\n"
            + "    float rim = 1.0 - smoothstep(0.0, max(" + U_EDGE_WIDTH_PX + ", 1.0), -d);\n"
            + "    col.rgb += " + U_SPECULAR + " * 0.55 * rim * " + U_HIGHLIGHT + ";\n"
            + "\n"
            + "    // 相位呼吸微光（每条通知一个固定相位，不逐帧刷新，零常驻功耗）\n"
            + "    float breathe = 0.5 + 0.5 * sin(" + U_PHASE + ");\n"
            + "    col.rgb += " + U_SPECULAR + " * 0.06 * breathe * rim * " + U_HIGHLIGHT + ";\n"
            + "\n"
            + "    // ---------- 动态光效（实时渲染引擎逐帧驱动；静态层管物理，本层管活着） ----------\n"
            + "    // 光源方向：屏幕空间 + 深度倾角，每帧由交互状态（触摸点/手势/入场进度）重算\n"
            + "    float3 lightDir = normalize(float3(" + U_LIGHT_DIR + ".x, " + U_LIGHT_DIR + ".y, 0.6));\n"
            + "    // 方向性镜面高光：半角向量 H = normalize(L + V)——光源滑动时高光在玻璃表面真实滑行，\n"
            + "    // 与上方固定方向的菲涅尔附加项叠加出活玻璃质感\n"
            + "    float3 halfV = normalize(lightDir + float3(0.0, 0.0, 1.0));\n"
            + "    float specDir = pow(max(dot(n, halfV), 0.0), 32.0);\n"
            + "    // 环境光越强高光越锐利，越暗越柔——模拟显示环境动态范围对高光形态的影响\n"
            + "    col.rgb += " + U_SPECULAR + " * 0.45 * specDir * (0.4 + 0.6 * " + U_AMBIENT + ") * " + U_HIGHLIGHT + ";\n"
            + "\n"
            + "    // 自适应接触阴影：背离光源一侧变暗，越靠近玻璃边缘（depth→0）越明显——\n"
            + "    // 真实光照下玻璃总在背光侧投下接触阴影，静态贴图永远摆不对这个方向。\n"
            + "    // 光源恰在卡片正中（length≈0）时回落默认方向，防 normalize 产生 NaN\n"
            + "    float2 ld2 = " + U_LIGHT_DIR + ";\n"
            + "    float ldl = length(ld2);\n"
            + "    float2 lightFlat = ldl > 0.001 ? ld2 / ldl : float2(0.35, -0.6);\n"
            + "    float facing = dot(outward, lightFlat) * 0.5 + 0.5;\n"
            + "    col.rgb *= 1.0 - " + U_SHADOW + " * (1.0 - facing) * (1.0 - depth) * 0.85;\n"
            + "\n"
            + "    // 流动光泽：随时间持续推进的对角光带（" + U_FLOW + " 由帧回调推进，交互时加速）\n"
            + "    float flow = 0.5 + 0.5 * sin(" + U_FLOW + " + centered.x * 0.012 - centered.y * 0.020);\n"
            + "    col.rgb += " + U_SPECULAR + " * 0.08 * flow * " + U_AMBIENT + " * " + U_HIGHLIGHT + ";\n"
            + "\n"
            + "    return col;\n"
            + "}\n";

    public static String source() {
        return SOURCE;
    }
}
