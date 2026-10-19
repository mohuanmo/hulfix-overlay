package com.example.hulfix.core;

import com.example.hulfix.overlay.StyleRegistry;
import com.example.hulfix.overlay.StyleSpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 插件管理：导入 / 校验 / 启停 / 删除「样式 + 引擎」插件包。
 *
 * <p><b>插件是什么</b>：一个 JSON 包，可携带</p>
 * <ul>
 *   <li>{@code styles}：一个或多个样式定义（与自定义样式导入完全同构，
 *       导入后进入样式列表，立即可选）；</li>
 *   <li>{@code engine}：一个引擎预设（一组 AGSL 真折射 / 动态光效参数），
 *       在插件页一键应用，无需记住每个滑块的数值。</li>
 * </ul>
 *
 * <p><b>存储模型</b>：插件记录（含其样式与预设）整体存在
 * {@code hulfix_plugins}；插件<b>启用</b>时它的样式会镜像进
 * {@code hulfix_custom_styles}（悬浮窗渲染只认后者，因此 hook 进程零改动）；
 * <b>停用</b>时从镜像中移除、记录仍保留，随时可再启用。删除则连同记录与
 * 镜像样式一起移除。</p>
 *
 * <p><b>导入规则（write rules）</b>：</p>
 * <ol>
 *   <li>根必须是 JSON 对象，且 {@code name} 非空——否则拒绝；</li>
 *   <li>{@code styles} 逐个经 {@link StyleSpec} 解析 + clamp，
 *       非法字段按默认值回落，不会让整个包失败；</li>
 *   <li>每个样式 id 重写为 {@code plugin_<pluginId>_<序号>}，避免与导入样式撞车；</li>
 *   <li>未知字段忽略；version / author / description 缺省为空。</li>
 * </ol>
 */
public final class PluginManager {

    public static final String KEY_PLUGINS = "hulfix_plugins";

    /** 引擎预设：一组具名的引擎参数（键为 hulfix_lens_* 设置键）。 */
    public static final class EnginePreset {
        public String name = "";
        public JSONObject params = new JSONObject();
    }

    /** 一个已安装的插件。 */
    public static final class Plugin {
        public String id = "";
        public String name = "";
        public int version = 1;
        public String author = "";
        public String description = "";
        public long installedAt;
        public boolean enabled = true;
        public List<StyleSpec> styles = new ArrayList<>();
        public EnginePreset engine; // 可为 null
    }

    private PluginManager() {
    }

    /** 解析插件存储 JSON；坏 JSON 返回空列表（绝不影响既有功能）。 */
    public static List<Plugin> parsePlugins(String json) {
        List<Plugin> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                Plugin p = fromJson(arr.getJSONObject(i));
                if (p != null) out.add(p);
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    public static String serializePlugins(List<Plugin> plugins) {
        JSONArray arr = new JSONArray();
        for (Plugin p : plugins) {
            try {
                arr.put(toJson(p));
            } catch (JSONException ignored) {
            }
        }
        return arr.toString();
    }

    public static Plugin fromJson(JSONObject o) {
        try {
            Plugin p = new Plugin();
            p.id = o.optString("id", "");
            p.name = o.optString("name", "");
            p.version = o.optInt("version", 1);
            p.author = o.optString("author", "");
            p.description = o.optString("description", "");
            p.installedAt = o.optLong("installedAt", 0);
            p.enabled = o.optBoolean("enabled", true);
            JSONArray styles = o.optJSONArray("styles");
            if (styles != null) {
                for (int i = 0; i < styles.length(); i++) {
                    try {
                        p.styles.add(StyleSpec.fromJson(styles.getJSONObject(i)));
                    } catch (JSONException ignored) {
                    }
                }
            }
            JSONObject eng = o.optJSONObject("engine");
            if (eng != null) {
                EnginePreset preset = new EnginePreset();
                preset.name = eng.optString("name", p.name + " 引擎预设");
                preset.params = eng.optJSONObject("params");
                if (preset.params == null) preset.params = new JSONObject();
                p.engine = preset;
            }
            return p.id.isEmpty() ? null : p;
        } catch (Throwable t) {
            return null;
        }
    }

    public static JSONObject toJson(Plugin p) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", p.id);
        o.put("name", p.name);
        o.put("version", p.version);
        o.put("author", p.author);
        o.put("description", p.description);
        o.put("installedAt", p.installedAt);
        o.put("enabled", p.enabled);
        JSONArray styles = new JSONArray();
        for (StyleSpec s : p.styles) styles.put(s.toJson());
        o.put("styles", styles);
        if (p.engine != null) {
            JSONObject eng = new JSONObject();
            eng.put("name", p.engine.name);
            eng.put("params", p.engine.params);
            o.put("engine", eng);
        }
        return o;
    }

    /**
     * 从用户提供的 JSON 文本解析出一个待安装插件。
     *
     * @param pluginId 调用方分配的插件 id（plugin_ 前缀 + 时间戳）
     * @return 插件；文本非法（非 JSON 对象 / name 为空 / 既无样式又无引擎预设）返回 null
     */
    public static Plugin parseImport(String json, String pluginId) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json.trim());
            String name = root.optString("name", "").trim();
            if (name.isEmpty()) return null;

            Plugin p = new Plugin();
            p.id = pluginId;
            p.name = name;
            p.version = root.optInt("version", 1);
            p.author = root.optString("author", "");
            p.description = root.optString("description", "");
            p.installedAt = System.currentTimeMillis();
            p.enabled = true;

            // 样式：与自定义样式导入同构（支持数组 / {"styles":[...]} 内嵌两种写法）
            JSONArray styles = root.optJSONArray("styles");
            if (styles != null) {
                for (int i = 0; i < styles.length(); i++) {
                    try {
                        p.styles.add(StyleSpec.fromJson(styles.getJSONObject(i)));
                    } catch (JSONException ignored) {
                    }
                }
            }
            // 引擎预设
            JSONObject eng = root.optJSONObject("engine");
            if (eng != null) {
                EnginePreset preset = new EnginePreset();
                preset.name = eng.optString("name", name + " 引擎预设");
                JSONObject params = eng.optJSONObject("params");
                preset.params = params != null ? params : new JSONObject();
                p.engine = preset;
            }
            if (p.styles.isEmpty() && p.engine == null) return null;

            // 样式 id 重写：plugin_<pluginId>_<序号>，避免撞车
            for (int i = 0; i < p.styles.size(); i++) {
                p.styles.get(i).id = "plugin_" + pluginId + "_" + i;
            }
            return p;
        } catch (JSONException e) {
            return null;
        }
    }

    /** 插件样式在自定义样式镜像中的 id 列表（用于启用 / 停用的增删）。 */
    public static List<String> styleIdsOf(Plugin p) {
        List<String> ids = new ArrayList<>();
        for (StyleSpec s : p.styles) ids.add(s.id);
        return ids;
    }

    /** 全部已启用插件携带的引擎预设（插件页"引擎预设"区展示）。 */
    public static List<EnginePreset> enginePresetsOf(List<Plugin> plugins) {
        List<EnginePreset> out = new ArrayList<>();
        for (Plugin p : plugins) {
            if (p.enabled && p.engine != null) out.add(p.engine);
        }
        return out;
    }

    /** 展示名（缺省回退）。 */
    public static String displayName(Plugin p) {
        return p.name == null || p.name.isEmpty() ? "未命名插件" : p.name;
    }

    /** 样式 id → 所属插件 id（plugin_<id>_<n> → <id>）；不属于插件返回 null。 */
    public static String pluginIdOfStyleId(String styleId) {
        if (styleId == null || !styleId.startsWith("plugin_")) return null;
        int last = styleId.lastIndexOf('_');
        if (last <= "plugin_".length() - 1) return null;
        return styleId.substring("plugin_".length(), last);
    }

    /** 已知引擎参数键白名单：预设只允许写这些键（防止乱键污染设置）。 */
    public static boolean isEngineParamKey(String key) {
        return "hulfix_lens_enabled".equals(key)
                || "hulfix_lens_thickness".equals(key)
                || "hulfix_lens_edge".equals(key)
                || "hulfix_lens_dispersion".equals(key)
                || "hulfix_lens_specular".equals(key)
                || "hulfix_lens_ior".equals(key)
                || "hulfix_lens_dynamic".equals(key)
                || "hulfix_lens_dynamic_strength".equals(key)
                || "hulfix_lens_adaptive_shadow".equals(key)
                || "hulfix_lens_accent_follow".equals(key);
    }
}
