package com.example.hulfix.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.core.widget.NestedScrollView;

import com.example.hulfix.core.Settings;
import com.example.hulfix.core.SettingsRepository;

/**
 * 设置页 Fragment 基类。
 *
 * <p>统一处理：限宽居中的滚动容器（平板适配）、与 {@link SettingsStore} 的生命周期绑定、
 * 进入前台时刷新控件状态。子类只需实现 {@link #buildUI} 与 {@link #refresh}。</p>
 */
public abstract class BaseSettingsFragment extends Fragment
        implements SettingsStore.ChangeListener {

    protected SettingsStore store;
    protected SharedPreferences prefs;
    private LinearLayout mContainer;

    // 刷新合并：滑块拖动 / 连续 put 会在短时间内触发多次 onSettingsDataChanged，
    // 合并到一次刷新，避免重建整个控件树（尤其样式页 6 张预览卡）造成卡顿。
    private final Handler mRefreshHandler = new Handler(Looper.getMainLooper());
    private static final long REFRESH_DEBOUNCE_MS = 100;
    private final Runnable mRefreshRunnable = this::refresh;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        store = SettingsStore.get(requireContext());
        prefs = store.prefs();
        mContainer = SettingsViews.createContainer(requireContext());
        buildUI(mContainer);
        refresh();
        NestedScrollView scroll = new NestedScrollView(requireContext());
        scroll.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.addView(SettingsViews.wrapCentered(requireContext(), mContainer));
        return scroll;
    }

    @Override
    public void onResume() {
        super.onResume();
        store.addListener(this);
        refresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        store.removeListener(this);
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
    }

    @Override
    public void onSettingsDataChanged() {
        if (!isAdded()) return;
        mRefreshHandler.removeCallbacks(mRefreshRunnable);
        mRefreshHandler.postDelayed(mRefreshRunnable, REFRESH_DEBOUNCE_MS);
    }

    /** 构建界面（仅调用一次）。 */
    protected abstract void buildUI(LinearLayout container);

    /** 刷新控件状态（每次 onResume / 数据变化时调用）。 */
    protected void refresh() {
    }

    // ==================== 读取帮助 ====================

    protected boolean prefBoolean(String key, boolean def) {
        return prefs.getBoolean(key, def);
    }

    protected int prefInt(String key, int def) {
        return prefs.getInt(key, def);
    }

    protected float prefFloat(String key, float def) {
        return prefs.getFloat(key, def);
    }

    protected String prefString(String key, String def) {
        return prefs.getString(key, def);
    }

    /** 当前完整设置快照（预览控件用）。 */
    protected Settings currentSettings() {
        return SettingsRepository.readSettings(prefs);
    }

    protected boolean isDarkNow() {
        int mode = prefInt("hulfix_theme_mode", Settings.THEME_AUTO);
        if (mode == Settings.THEME_LIGHT) return false;
        if (mode == Settings.THEME_DARK) return true;
        int uiMode = requireContext().getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }
}
