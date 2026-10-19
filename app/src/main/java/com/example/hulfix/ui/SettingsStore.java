package com.example.hulfix.ui;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.example.hulfix.core.PluginManager;
import com.example.hulfix.core.Settings;
import com.example.hulfix.core.SettingsRepository;
import com.example.hulfix.overlay.OverlayController;
import com.example.hulfix.overlay.StyleRegistry;
import com.example.hulfix.overlay.StyleSpec;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * App 进程侧的设置读写中枢。
 *
 * <p>唯一写入口径：所有设置修改都经过本类，落盘 SharedPreferences（与 hook 进程通过
 * {@link SettingsRepository} 的 XSharedPreferences 同文件读取）后，立即向 SystemUI
 * 发送 {@link SettingsRepository#ACTION_SETTINGS_CHANGED} 显式广播实现热更新。</p>
 *
 * <p>同时负责：hook 进程回报事件的持久化（统计计数、台账镜像、用户忽略列表）、
 * 自定义样式的导入管理、手动暂停的截止时间维护。</p>
 */
public final class SettingsStore {

    private static final String KEY_STAT_SHOWN = "stat_shown";
    private static final String KEY_STAT_SUPPRESSED = "stat_suppressed";
    private static final String KEY_STAT_DISMISSED = "stat_dismissed";
    private static final String KEY_STAT_DATE = "stat_date";
    private static final String KEY_LEDGER = "ledger_json";
    private static final int LEDGER_MAX = 100;

    private static SettingsStore sInstance;

    private final SharedPreferences mPrefs;

    private SettingsStore(Context ctx) {
        mPrefs = ctx.getApplicationContext()
                .getSharedPreferences(SettingsRepository.PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized SettingsStore get(Context ctx) {
        if (sInstance == null) {
            sInstance = new SettingsStore(ctx);
        }
        return sInstance;
    }

    public SharedPreferences prefs() {
        return mPrefs;
    }

    // ==================== 写设置 ====================

    private void apply(SharedPreferences.Editor editor) {
        // 设置版本号 +1：hook 进程以它作为"设置已变化"的主判据（比文件 mtime 可靠，
        // 部分 ROM 上 SystemUI 读不到 App 私有目录的 lastModified，会退化成"必须重启"）。
        editor.putLong(SettingsRepository.KEY_REVISION,
                mPrefs.getLong(SettingsRepository.KEY_REVISION, 0L) + 1);
        editor.apply();
        // 立即刷新 App 内 UI（例如样式卡勾选状态）。此前只发 SystemUI 广播、从不通知
        // 本地监听器，导致切换样式后"✓"必须等 onResume（划掉 App 重启）才出现。
        notifyListeners();
        notifySystemUi();
    }

    public void putBoolean(String key, boolean value) {
        apply(mPrefs.edit().putBoolean(key, value));
    }

    public void putInt(String key, int value) {
        apply(mPrefs.edit().putInt(key, value));
    }

    public void putFloat(String key, float value) {
        apply(mPrefs.edit().putFloat(key, value));
    }

    public void putLong(String key, long value) {
        apply(mPrefs.edit().putLong(key, value));
    }

    public void putString(String key, String value) {
        apply(mPrefs.edit().putString(key, value));
    }

    public void putStringSet(String key, Set<String> value) {
        apply(mPrefs.edit().putString(key, Settings.encodeSet(value)));
    }

    /** 广播通知 SystemUI 重新加载设置（150ms 合并：拖动滑块时避免广播洪水）。 */
    public void notifySystemUi() {
        if (sContext == null) return;
        mUiHandler.removeCallbacks(mNotifyRunnable);
        mUiHandler.postDelayed(mNotifyRunnable, 150);
    }

    /**
     * 立即广播通知 SystemUI（不等 150ms 合并）。
     *
     * <p>用于 SystemUI 重启后主动请求设置的响应路径：请求方正在等待，晚 150ms
     * 意味着悬浮窗多用默认设置弹一条。</p>
     */
    public void notifySystemUiNow() {
        if (sContext == null) return;
        mUiHandler.removeCallbacks(mNotifyRunnable);
        doNotifySystemUi();
    }

    private void doNotifySystemUi() {
        if (sContext == null) return;
        try {
            String json = SettingsRepository.readSettings(mPrefs).toJson();
            // 通道 1（主）：显式指定 SystemUI 包名，跨进程直达
            try {
                Intent intent = new Intent(SettingsRepository.ACTION_SETTINGS_CHANGED);
                intent.setPackage("com.android.systemui");
                intent.putExtra(SettingsRepository.EXTRA_SETTINGS_JSON, json);
                sContext.sendBroadcast(intent);
            } catch (Throwable ignored) {
            }
            // 通道 2（冗余）：不带包名的隐式广播——动态注册的接收器可以收到，
            // 覆盖个别 ROM SystemUI 包名变体 / 显式广播被裁剪的情况。
            // 两条都送达时 hook 端解析两次相同 JSON，幂等无副作用。
            try {
                Intent intent2 = new Intent(SettingsRepository.ACTION_SETTINGS_CHANGED);
                intent2.putExtra(SettingsRepository.EXTRA_SETTINGS_JSON, json);
                sContext.sendBroadcast(intent2);
            } catch (Throwable ignored) {
            }
        } catch (Throwable t) {
            // 构造/序列化失败绝不能再静默：这正是"改设置不生效"最难排查的形态
            android.util.Log.e("HULFix", "doNotifySystemUi failed", t);
        }
    }

    private final android.os.Handler mUiHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mNotifyRunnable = this::doNotifySystemUi;

    private static Context sContext;

    public static void init(Context ctx) {
        sContext = ctx.getApplicationContext();
        get(ctx);
    }

    // ==================== 统计 ====================

    /** 跨天自动清零。返回 [今日展示, 今日抑制, 今日划掉]。 */
    public int[] stats() {
        rollDateIfNeeded();
        return new int[]{
                mPrefs.getInt(KEY_STAT_SHOWN, 0),
                mPrefs.getInt(KEY_STAT_SUPPRESSED, 0),
                mPrefs.getInt(KEY_STAT_DISMISSED, 0)
        };
    }

    public void resetStats() {
        apply(mPrefs.edit()
                .putInt(KEY_STAT_SHOWN, 0)
                .putInt(KEY_STAT_SUPPRESSED, 0)
                .putInt(KEY_STAT_DISMISSED, 0));
    }

    private void rollDateIfNeeded() {
        String today = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
        if (!today.equals(mPrefs.getString(KEY_STAT_DATE, ""))) {
            mPrefs.edit()
                    .putString(KEY_STAT_DATE, today)
                    .putInt(KEY_STAT_SHOWN, 0)
                    .putInt(KEY_STAT_SUPPRESSED, 0)
                    .putInt(KEY_STAT_DISMISSED, 0)
                    .apply();
        }
    }

    // ==================== hook 事件回报 ====================

    /** 由 {@link StatsReceiver} 调用。 */
    public void onStatEvent(String event, String pkg, String key, String title, int reason) {
        rollDateIfNeeded();
        SharedPreferences.Editor editor = mPrefs.edit();
        switch (event) {
            case OverlayController.EVENT_SHOWN:
                editor.putInt(KEY_STAT_SHOWN, mPrefs.getInt(KEY_STAT_SHOWN, 0) + 1);
                upsertLedger(key, pkg, title, reason, true);
                break;
            case OverlayController.EVENT_SUPPRESSED:
                editor.putInt(KEY_STAT_SUPPRESSED, mPrefs.getInt(KEY_STAT_SUPPRESSED, 0) + 1);
                upsertLedger(key, pkg, title, reason, false);
                break;
            case OverlayController.EVENT_DISMISSED:
                editor.putInt(KEY_STAT_DISMISSED, mPrefs.getInt(KEY_STAT_DISMISSED, 0) + 1);
                addIgnored(key, pkg, title);
                break;
            default:
                return;
        }
        editor.apply();
        notifyListeners();
    }

    // ==================== 台账镜像 ====================

    private void upsertLedger(String key, String pkg, String title, int reason, boolean shown) {
        if (key == null || key.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray(mPrefs.getString(KEY_LEDGER, "[]"));
            JSONObject entry = null;
            int index = -1;
            for (int i = 0; i < arr.length(); i++) {
                if (key.equals(arr.getJSONObject(i).optString("key"))) {
                    entry = arr.getJSONObject(i);
                    index = i;
                    break;
                }
            }
            if (entry == null) {
                entry = new JSONObject();
                entry.put("key", key);
                entry.put("pkg", pkg);
                entry.put("title", title);
                entry.put("shown", 0);
                entry.put("suppressed", 0);
            }
            if (shown) entry.put("shown", entry.optInt("shown") + 1);
            else entry.put("suppressed", entry.optInt("suppressed") + 1);
            entry.put("reason", reason);
            entry.put("lastEvent", System.currentTimeMillis());

            JSONArray out = new JSONArray();
            out.put(entry);
            for (int i = 0; i < arr.length(); i++) {
                if (i == index) continue;
                if (out.length() >= LEDGER_MAX) break;
                out.put(arr.getJSONObject(i));
            }
            mPrefs.edit().putString(KEY_LEDGER, out.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public String ledgerJson() {
        return mPrefs.getString(KEY_LEDGER, "[]");
    }

    public void clearLedger() {
        apply(mPrefs.edit().putString(KEY_LEDGER, "[]"));
    }

    // ==================== 用户忽略列表（与 hook 进程共享 hulfix_ignored） ====================

    public List<Settings.IgnoredEntry> ignoredEntries() {
        return SettingsRepository.parseIgnored(mPrefs.getString("hulfix_ignored", "[]"));
    }

    public void addIgnored(String key, String pkg, String title) {
        if (key == null || key.isEmpty()) return;
        List<Settings.IgnoredEntry> list = ignoredEntries();
        list.removeIf(e -> key.equals(e.key));
        list.add(0, new Settings.IgnoredEntry(key, pkg, title, System.currentTimeMillis()));
        while (list.size() > 100) list.remove(list.size() - 1);
        saveIgnored(list);
    }

    public void removeIgnored(String key) {
        List<Settings.IgnoredEntry> list = ignoredEntries();
        list.removeIf(e -> key.equals(e.key));
        saveIgnored(list);
    }

    public void clearIgnored() {
        saveIgnored(new ArrayList<>());
    }

    private void saveIgnored(List<Settings.IgnoredEntry> list) {
        JSONArray arr = new JSONArray();
        for (Settings.IgnoredEntry e : list) {
            try {
                JSONObject o = new JSONObject();
                o.put("key", e.key);
                o.put("pkg", e.pkg);
                o.put("title", e.title);
                o.put("at", e.atWallMs);
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        apply(mPrefs.edit().putString("hulfix_ignored", arr.toString()));
    }

    // ==================== 自定义样式 ====================

    public List<StyleSpec> customStyles() {
        return StyleRegistry.parseCustom(mPrefs.getString("hulfix_custom_styles", "[]"));
    }

    public void addCustomStyle(StyleSpec spec) {
        List<StyleSpec> list = customStyles();
        list.removeIf(s -> spec.id.equals(s.id));
        list.add(0, spec);
        saveCustomStyles(list);
    }

    public void removeCustomStyle(String id) {
        List<StyleSpec> list = customStyles();
        list.removeIf(s -> id.equals(s.id));
        saveCustomStyles(list);
    }

    private void saveCustomStyles(List<StyleSpec> list) {
        JSONArray arr = new JSONArray();
        for (StyleSpec s : list) {
            try {
                arr.put(s.toJson());
            } catch (JSONException ignored) {
            }
        }
        apply(mPrefs.edit().putString("hulfix_custom_styles", arr.toString()));
    }

    // ==================== 插件 ====================

    public List<PluginManager.Plugin> plugins() {
        return PluginManager.parsePlugins(mPrefs.getString(PluginManager.KEY_PLUGINS, "[]"));
    }

    private void savePlugins(List<PluginManager.Plugin> list) {
        apply(mPrefs.edit().putString(PluginManager.KEY_PLUGINS,
                PluginManager.serializePlugins(list)));
    }

    /**
     * 安装插件：记录落盘 + 样式镜像进 customStyles（悬浮窗只认后者）。
     *
     * <p>镜像同步在每次插件结构变化后重算：customStyles = 纯导入样式 + 全部
     * <b>启用</b>插件的样式。停用即从镜像消失、记录保留，可随时再启用。</p>
     */
    public void installPlugin(PluginManager.Plugin p) {
        List<PluginManager.Plugin> list = plugins();
        list.removeIf(x -> p.id.equals(x.id));
        list.add(0, p);
        savePlugins(list);
        syncCustomStylesMirror();
    }

    public void setPluginEnabled(String id, boolean enabled) {
        List<PluginManager.Plugin> list = plugins();
        boolean changed = false;
        for (PluginManager.Plugin p : list) {
            if (id.equals(p.id) && p.enabled != enabled) {
                p.enabled = enabled;
                changed = true;
            }
        }
        if (changed) {
            savePlugins(list);
            syncCustomStylesMirror();
        }
    }

    public void removePlugin(String id) {
        List<PluginManager.Plugin> list = plugins();
        boolean changed = list.removeIf(p -> id.equals(p.id));
        if (changed) {
            savePlugins(list);
            syncCustomStylesMirror();
        }
    }

    /** 重算 customStyles 镜像（导入样式 + 启用插件样式）。 */
    private void syncCustomStylesMirror() {
        List<StyleSpec> merged = new ArrayList<>();
        for (StyleSpec s : customStyles()) {
            // 只保留纯导入样式（非插件镜像）
            if (PluginManager.pluginIdOfStyleId(s.id) == null) merged.add(s);
        }
        for (PluginManager.Plugin p : plugins()) {
            if (p.enabled) merged.addAll(p.styles);
        }
        saveCustomStyles(merged);
    }

    /** 应用插件携带的引擎预设（只写白名单内的 hulfix_lens_* 键）。 */
    public void applyEnginePreset(PluginManager.EnginePreset preset) {
        if (preset == null || preset.params == null) return;
        SharedPreferences.Editor e = mPrefs.edit();
        try {
            java.util.Iterator<String> it = preset.params.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (!PluginManager.isEngineParamKey(key)) continue;
                Object v = preset.params.get(key);
                if (v instanceof Boolean) {
                    e.putBoolean(key, (Boolean) v);
                } else if (v instanceof Number) {
                    // 引擎参数在 prefs 中全部为 float 语义
                    e.putFloat(key, ((Number) v).floatValue());
                }
            }
        } catch (Throwable ignored) {
        }
        apply(e);
    }

    // ==================== 手动暂停 ====================

    public void setPause(long durationMs) {
        apply(mPrefs.edit().putLong("hulfix_pause_until_wall_ms",
                System.currentTimeMillis() + durationMs));
    }

    public void clearPause() {
        apply(mPrefs.edit().putLong("hulfix_pause_until_wall_ms", 0));
    }

    public long pauseRemainingMs() {
        long until = mPrefs.getLong("hulfix_pause_until_wall_ms", 0);
        if (until <= 0) return 0;
        return Math.max(0, until - System.currentTimeMillis());
    }

    // ==================== 变化监听（UI 刷新） ====================

    public interface ChangeListener {
        void onSettingsDataChanged();
    }

    private final List<ChangeListener> mListeners = new ArrayList<>();

    public void addListener(ChangeListener l) {
        synchronized (mListeners) {
            mListeners.add(l);
        }
    }

    public void removeListener(ChangeListener l) {
        synchronized (mListeners) {
            mListeners.remove(l);
        }
    }

    private void notifyListeners() {
        synchronized (mListeners) {
            for (ChangeListener l : new ArrayList<>(mListeners)) {
                l.onSettingsDataChanged();
            }
        }
    }
}
