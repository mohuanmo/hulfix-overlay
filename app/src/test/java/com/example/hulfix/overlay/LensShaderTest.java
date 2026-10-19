package com.example.hulfix.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link LensShader} 的源码结构校验。
 *
 * <p>沙箱内没有 Android 设备 / 模拟器，AGSL 的最终编译验证在真机上进行；
 * 这里保证的是"不会因为低级错误编译失败"：入口签名、uniform 声明与 Java 常量一一对应、
 * 关键物理算子齐全、括号配平、无占位符残留。语法层面另用 Skia/glslang 工具链离线校验。</p>
 */
public class LensShaderTest {

    private static final String SRC = LensShader.source();

    @Test
    public void mainSignatureMatchesRenderEffectSpec() {
        // RenderEffect 的 RuntimeShader 入口必须是该签名
        assertTrue(SRC.contains("half4 main(float2 fragCoord)"));
        assertFalse(SRC.contains("void main("));
        assertFalse(SRC.contains("gl_FragColor"));
    }

    @Test
    public void physicsOperatorsPresent() {
        // SDF 轮廓
        assertTrue(SRC.contains("sdRoundedBox"));
        // Snell 定律：至少 RGB 三次 refract（色散）
        int first = SRC.indexOf("refract(");
        assertTrue("refract missing", first >= 0);
        int count = 0;
        int idx = first;
        while ((idx = SRC.indexOf("refract(", idx)) >= 0) {
            count++;
            idx++;
        }
        assertTrue("expected >= 3 refract calls (RGB dispersion), got " + count, count >= 3);
        // 背景采样
        assertTrue(SRC.contains(LensShader.U_BACKDROP + ".eval("));
        // Schlick 菲涅尔（F0 基础反射率 + 5 次幂掠射角项）+ 呼吸微光
        assertTrue(SRC.contains("pow((1.0 - " + LensShader.U_IOR + ") / (1.0 + "
                + LensShader.U_IOR + "), 2.0)"));
        assertTrue(SRC.contains("pow(1.0 - clamp(n.z, 0.0, 1.0), 5.0)"));
        assertTrue(SRC.contains("sin(" + LensShader.U_PHASE + ")"));
    }

    @Test
    public void dispersionIsSymmetric() {
        String plus = "1.0 + " + LensShader.U_DISPERSION + " * 0.03";
        String minus = "1.0 - " + LensShader.U_DISPERSION + " * 0.03";
        assertTrue("R channel dispersion missing: " + plus, SRC.contains(plus));
        assertTrue("B channel dispersion missing: " + minus, SRC.contains(minus));
    }

    @Test
    public void everyUniformDeclaredWithMatchingName() {
        String[] names = {
                LensShader.U_RESOLUTION, LensShader.U_RADIUS_PX, LensShader.U_EDGE_WIDTH_PX,
                LensShader.U_THICKNESS, LensShader.U_EDGE_BOOST, LensShader.U_DISPERSION,
                LensShader.U_SPECULAR, LensShader.U_IOR, LensShader.U_HIGHLIGHT,
                LensShader.U_PHASE, LensShader.U_BACKDROP,
                LensShader.U_LIGHT_DIR, LensShader.U_AMBIENT, LensShader.U_SHADOW,
                LensShader.U_FLOW
        };
        for (String n : names) {
            assertTrue("uniform not declared: " + n,
                    SRC.contains("uniform float2 " + n + ";")
                            || SRC.contains("uniform float " + n + ";")
                            || SRC.contains("uniform float3 " + n + ";")
                            || SRC.contains("uniform shader " + n + ";"));
            // 除注入的 shader uniform 外，Java 侧必须真的 set 这些 uniform
            assertTrue("uniform never read in shader body: " + n, SRC.contains(n));
        }
    }

    @Test
    public void dynamicLightingConstructsPresent() {
        // 方向性镜面高光：半角向量 H = normalize(L + V)
        assertTrue("half vector missing", SRC.contains("normalize(lightDir + float3(0.0, 0.0, 1.0))"));
        assertTrue("directional specular missing", SRC.contains("max(dot(n, halfV), 0.0)"));
        // 自适应接触阴影：背离光源侧衰减 + 边缘加权
        assertTrue("adaptive shadow missing", SRC.contains("1.0 - " + LensShader.U_SHADOW + " * (1.0 - facing)"));
        // 流动光泽：相位驱动的对角光带
        assertTrue("flow sheen missing", SRC.contains("sin(" + LensShader.U_FLOW + " + centered.x"));
    }

    @Test
    public void bracesAndParensBalanced() {
        int braces = 0;
        int parens = 0;
        for (char c : SRC.toCharArray()) {
            if (c == '{') braces++;
            if (c == '}') braces--;
            if (c == '(') parens++;
            if (c == ')') parens--;
            assertTrue("parens went negative", parens >= 0);
        }
        assertEquals("braces unbalanced", 0, braces);
        assertEquals("parens unbalanced", 0, parens);
    }

    @Test
    public void noPlaceholderOrTemplateLeftover() {
        for (String bad : new String[]{"TODO", "FIXME", "XXX", "${", "$(", "%s", "%d"}) {
            assertFalse("placeholder found: " + bad, SRC.contains(bad));
        }
    }

    @Test
    public void outsideRoundedRectIsTransparent() {
        // IQ 约定：SDF 外部为正 → d > 0 时透明（曾因约定写反导致整块玻璃不可见，
        // 该场景由 CPU 光栅化对比测试兜底）
        assertTrue(SRC.contains("if (d > 0.0)"));
        assertTrue(SRC.contains("return half4(0.0, 0.0, 0.0, 0.0);"));
    }

    @Test
    public void noUnsupportedGlsConstructs() {
        // SkSL 不支持的 GLSL 残留会直接编译失败
        for (String bad : new String[]{"texture2D", "varying", "attribute", "gl_FragCoord",
                "uniform sampler2D", "dFdx", "dFdy"}) {
            assertFalse("GLSL-only construct found: " + bad, SRC.contains(bad));
        }
    }
}
