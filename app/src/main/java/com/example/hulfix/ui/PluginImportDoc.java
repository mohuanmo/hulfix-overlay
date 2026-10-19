package com.example.hulfix.ui;

/**
 * 插件 JSON 规范（内置到 App 内，插件页"查看插件规范"入口展示）。
 *
 * <p>插件 = 一个 JSON 包，可携带若干<b>样式</b>与一个<b>引擎预设</b>：
 * 样式进入样式列表立即可选；引擎预设是一组 AGSL 真折射 / 动态光效参数，
 * 在插件页一键应用。解析与校验规则以 {@link com.example.hulfix.core.PluginManager}
 * 为准，本文档只作说明；{@link #EXAMPLE_JSON} 被单元测试引用
 * （{@code PluginImportTest}），保证文档示例真的能导入。</p>
 */
public final class PluginImportDoc {

    private PluginImportDoc() {
    }

    /** 完整规范（等宽字体展示，插件页"查看插件规范"对话框使用）。 */
    public static final String SPEC_TEXT =
            "【插件文件形态】\n"
                    + "  单个 JSON 对象：{ ... }\n"
                    + "  （文件后缀不限 .json / .txt，内容合法即可）\n"
                    + "\n"
                    + "【根字段】\n"
                    + "  name        字符串  必填  插件名（空则拒绝导入）\n"
                    + "  version     整数    可选  默认 1\n"
                    + "  author      字符串  可选  作者署名\n"
                    + "  description 字符串  可选  插件说明\n"
                    + "  styles      数组    可选  样式定义列表（与自定义样式同构）\n"
                    + "  engine      对象    可选  引擎预设（见下）\n"
                    + "  注：styles 与 engine 至少有一个，否则拒绝导入\n"
                    + "\n"
                    + "【styles 数组】\n"
                    + "  每项即一个完整样式定义，字段与「自定义样式规范」一致\n"
                    + "  （variant / dark / light / 几何 / 显隐 / 动画……）。\n"
                    + "  导入后 id 自动重写为 plugin_<插件id>_<序号>，无需填写。\n"
                    + "\n"
                    + "【engine 引擎预设】\n"
                    + "  {\"name\": \"预设名\", \"params\": { ... }}\n"
                    + "  params 只接受以下键（布尔 / 数值），未知键忽略：\n"
                    + "    hulfix_lens_enabled            真折射总开关\n"
                    + "    hulfix_lens_thickness          折射强度 px（0~48）\n"
                    + "    hulfix_lens_edge               边缘透镜 0~1\n"
                    + "    hulfix_lens_dispersion         色散 0~1\n"
                    + "    hulfix_lens_specular           镜面高光 0~2\n"
                    + "    hulfix_lens_ior                折射率 1.01~2\n"
                    + "    hulfix_lens_dynamic            动态光效总开关\n"
                    + "    hulfix_lens_dynamic_strength   动态强度 0~1\n"
                    + "    hulfix_lens_adaptive_shadow    自适应阴影 0~1\n"
                    + "    hulfix_lens_accent_follow      高光跟随系统强调色\n"
                    + "\n"
                    + "【导入方式】\n"
                    + "  1) 从文件导入（选择 .json / .txt）\n"
                    + "  2) 粘贴 JSON 导入\n"
                    + "\n"
                    + "【启用 / 停用】\n"
                    + "  停用只把插件样式从可选列表移除，记录与预设保留；\n"
                    + "  再次启用即刻恢复。删除则连同样式与预设一并移除。\n";

    /** 示例插件（单对象形态，含 1 个样式 + 1 个引擎预设）。 */
    public static final String EXAMPLE_JSON =
            "{\n"
                    + "  \"name\": \"极简薄雾\",\n"
                    + "  \"version\": 1,\n"
                    + "  \"author\": \"HULFix 社区\",\n"
                    + "  \"description\": \"低透明度磨砂玻璃 + 强折射引擎预设\",\n"
                    + "  \"styles\": [\n"
                    + "    {\n"
                    + "      \"name\": \"薄雾\",\n"
                    + "      \"variant\": \"liquid\",\n"
                    + "      \"cornerRadiusDp\": 22,\n"
                    + "      \"strokeWidthDp\": 1,\n"
                    + "      \"blurRadiusDp\": 30,\n"
                    + "      \"light\": {\n"
                    + "        \"bgColors\": [\"#33FFFFFF\", \"#1FFFFFFF\"],\n"
                    + "        \"accent\": \"#FF007AFF\"\n"
                    + "      },\n"
                    + "      \"dark\": {\n"
                    + "        \"bgColors\": [\"#40000000\", \"#26000000\"],\n"
                    + "        \"accent\": \"#FF0A84FF\"\n"
                    + "      }\n"
                    + "    }\n"
                    + "  ],\n"
                    + "  \"engine\": {\n"
                    + "    \"name\": \"强折射薄玻璃\",\n"
                    + "    \"params\": {\n"
                    + "      \"hulfix_lens_thickness\": 22,\n"
                    + "      \"hulfix_lens_edge\": 0.9,\n"
                    + "      \"hulfix_lens_dispersion\": 0.5,\n"
                    + "      \"hulfix_lens_specular\": 1.2,\n"
                    + "      \"hulfix_lens_dynamic\": true\n"
                    + "    }\n"
                    + "  }\n"
                    + "}";
}
