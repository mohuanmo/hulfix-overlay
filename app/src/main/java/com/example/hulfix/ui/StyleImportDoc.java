package com.example.hulfix.ui;

/**
 * 自定义样式 JSON 规范（内置到 App 内，样式页"查看样式规范"入口展示）。
 *
 * <p>本规范与 {@link com.example.hulfix.overlay.StyleSpec} 的字段一一对应：
 * 解析、默认值回落、取值范围夹紧（clamp）均以 StyleSpec 为准，本文档只作说明。
 * {@link #EXAMPLE_JSON} 同时被单元测试引用（{@code StyleImportTest}），
 * 保证"文档里写的示例真的能导入"——文档与实现不会悄悄漂移。</p>
 *
 * <p>三种文件形态（任选其一）：</p>
 * <ol>
 *   <li>单个样式对象：{@code { ... }}</li>
 *   <li>样式数组：{@code [ { ... }, { ... } ]}</li>
 *   <li>包装对象：{@code { "styles": [ { ... }, { ... } ] }}</li>
 * </ol>
 */
public final class StyleImportDoc {

    private StyleImportDoc() {
    }

    /** 完整字段规范（等宽字体展示，设置页"查看样式规范"对话框使用）。 */
    public static final String SPEC_TEXT =
            "【文件形态】三选一\n"
                    + "  1) 单个样式对象  { ... }\n"
                    + "  2) 样式数组      [ {...}, {...} ]\n"
                    + "  3) 包装对象      {\"styles\":[{...}]}\n"
                    + "\n"
                    + "【通用字段】\n"
                    + "  id           字符串  可选  导入时自动分配 custom_ 前缀，无需填写\n"
                    + "  name         字符串  可选  样式名称，默认\"自定义样式\"\n"
                    + "  author       字符串  可选  作者署名，默认空\n"
                    + "  version      整数    可选  默认 1\n"
                    + "  variant      字符串  可选  渲染策略：\n"
                    + "                          standard 标准安卓 / glass 磨砂玻璃 /\n"
                    + "                          liquid 液态玻璃 / neon 霓虹脉冲\n"
                    + "                          默认 glass；非法值回落 glass\n"
                    + "  builtin      布尔    可选  默认 false（导入样式恒视为非内置）\n"
                    + "\n"
                    + "【配色】dark / light 两个对象（深色 / 浅色两套），\n"
                    + "  颜色为 #RRGGBB 或 #AARRGGBB；缺失 / 非法回落默认值\n"
                    + "  bgColors       字符串数组  2~3 个背景渐变色（少于 2 个回落默认）\n"
                    + "  textPrimary    主文字色      默认 #FFFFFFFF\n"
                    + "  textSecondary  次文字色      默认 #FFCCCCCC\n"
                    + "  appNameColor   应用名字色    默认 #FF999999\n"
                    + "  accent         强调色        默认 #FFA8C7FA\n"
                    + "  strokeColor    描边色        默认 #30FFFFFF\n"
                    + "  highlightColor 顶部高光色    默认 #40FFFFFF\n"
                    + "  shadowColor    底部阴影色    默认 #40000000\n"
                    + "  iconBgColor    图标容器底色  默认 #20A8C7FA\n"
                    + "  iconStrokeColor 图标容器描边 默认 #60A8C7FA\n"
                    + "  ringColor      进度环色      默认 #FFA8C7FA\n"
                    + "  ringTrackColor 进度环轨道色  默认 #30FFFFFF\n"
                    + "  pulseColor     脉冲点颜色    默认 #FFA8C7FA\n"
                    + "\n"
                    + "【几何与排版】单位 dp / sp，越界自动夹紧\n"
                    + "  cornerRadiusDp  0~48    默认 28   卡片圆角\n"
                    + "  strokeWidthDp   0~4     默认 1    描边宽度\n"
                    + "  blurRadiusDp    0~60    默认 0    背景模糊半径\n"
                    + "                        （>0 且 API 31+ 时启用有界窗口模糊）\n"
                    + "  scrimColor      颜色    默认 #00000000  背景罩色（0=不加罩）\n"
                    + "  paddingHDp      0~64    默认 20   水平内边距\n"
                    + "  paddingVDp      0~48    默认 14   垂直内边距\n"
                    + "  iconSizeDp      16~96   默认 40   图标尺寸\n"
                    + "  iconPaddingDp   0~24    默认 6    图标容器内边距\n"
                    + "  titleSizeSp     8~32    默认 15   标题字号\n"
                    + "  textSizeSp      8~28    默认 13   正文字号\n"
                    + "  appNameSizeSp   6~20    默认 11   应用名字号\n"
                    + "  elevationDp     0~32    默认 8    投影高度\n"
                    + "  minHeightDp     32~200  默认 64   卡片最小高度\n"
                    + "\n"
                    + "【元素开关】布尔；showIcon / showAppName 默认 true，其余默认 false\n"
                    + "  showIcon 显示图标        showAppName 显示应用名\n"
                    + "  iconCircleBg 图标圆形底  progressRing 倒计时进度环\n"
                    + "  pulseDot 呼吸脉冲点      topHighlight 顶部镜面高光\n"
                    + "  bottomShadow 底部接触阴影 doubleStroke 双描边（折射感）\n"
                    + "  maxTextLines  1~5  默认 2   正文最大行数\n"
                    + "\n"
                    + "【强调色模式】\n"
                    + "  accentMode  0=固定取 accent（默认）\n"
                    + "              1=从通知图标动态取色\n"
                    + "\n"
                    + "【动画默认值】用户在动画页选\"跟随样式\"时使用\n"
                    + "  enterAnim / exitAnim  0=跟随样式 1=滑动 2=缩放 3=弹性 4=淡入\n"
                    + "                        默认 1（滑动）\n"
                    + "  enterDurationMs  60~1500   默认 260\n"
                    + "  exitDurationMs   60~1500   默认 240\n"
                    + "  enterOvershoot   0~3       默认 1.0（弹性强度）\n"
                    + "\n"
                    + "【示例】见\"复制示例\"按钮（可直接粘贴导入）";

    /**
     * 规范示例 JSON：同时被 {@code StyleImportTest} 用于验证"文档示例真实可导入"，
     * 修改本常量时若与 StyleSpec 不兼容，测试会失败提醒。
     */
    public static final String EXAMPLE_JSON =
            "{\n"
                    + "  \"name\": \"暮光紫\",\n"
                    + "  \"author\": \"HULFix\",\n"
                    + "  \"variant\": \"glass\",\n"
                    + "  \"cornerRadiusDp\": 26,\n"
                    + "  \"strokeWidthDp\": 1,\n"
                    + "  \"blurRadiusDp\": 20,\n"
                    + "  \"iconCircleBg\": true,\n"
                    + "  \"topHighlight\": true,\n"
                    + "  \"bottomShadow\": true,\n"
                    + "  \"dark\": {\n"
                    + "    \"bgColors\": [\"#E61F1B2E\", \"#B81F1B2E\"],\n"
                    + "    \"textPrimary\": \"#FFFFFFFF\",\n"
                    + "    \"textSecondary\": \"#CCEBEBF5\",\n"
                    + "    \"accent\": \"#FFCFBCFF\"\n"
                    + "  },\n"
                    + "  \"light\": {\n"
                    + "    \"bgColors\": [\"#D9FFFFFF\", \"#B3FFFFFF\"],\n"
                    + "    \"textPrimary\": \"#FF1C1B1F\",\n"
                    + "    \"textSecondary\": \"#CC49454F\",\n"
                    + "    \"accent\": \"#FF6750A4\"\n"
                    + "  }\n"
                    + "}";
}
