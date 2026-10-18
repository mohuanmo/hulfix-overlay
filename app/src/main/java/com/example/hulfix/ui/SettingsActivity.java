package com.example.hulfix.ui;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.example.hulfix.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.navigationrail.NavigationRailView;

/**
 * HULFix 设置主界面。
 *
 * <p><b>平板/折叠屏适配</b>：屏幕最短边 ≥ 600dp 时自动切换为左侧导航栏
 * （NavigationRailView）+ 右侧内容的横向布局；否则使用底部导航
 * （BottomNavigationView）。两套导航共享同一份菜单与 Fragment 集合，
 * 所有页面内容均限宽 640dp 居中，长宽比变化不会导致文字溢出或控件偏移。</p>
 *
 * <p><b>页面切换零重建</b>：五个页面 Fragment 首次进入时 {@code add}，
 * 之后切换只用 {@code show()/hide()}——切页不销毁视图树。旧实现用
 * {@code replace()}，每次切换都重建整棵视图树：样式页的预览控件每个都会
 * 触发一次 AGSL 真折射着色器编译（RuntimeShader 编译是毫秒级重操作），
 * 表现为切页掉帧/卡顿。show/hide 后切换仅一次 alpha 转场动画。</p>
 */
public class SettingsActivity extends AppCompatActivity {

    private static final int[] NAV_IDS = {
            R.id.nav_style, R.id.nav_anim, R.id.nav_position, R.id.nav_apps, R.id.nav_engine
    };
    private static final int[] TITLE_IDS = {
            R.string.nav_style, R.string.nav_anim, R.string.nav_position,
            R.string.nav_apps, R.string.nav_engine
    };

    /**
     * 主题色偏好：0 = 跟随壁纸动态取色（Material You，Android 12+ 生效）；
     * 1..8 = 预设种子色，对应 {@link #SEED_OVERLAYS}。
     *
     * <p>本地 UI 偏好：只影响本 App 设置界面观感，<b>不</b>进入悬浮窗设置
     * （不随 {@code ACTION_SETTINGS_CHANGED} 广播下发，与 hulfix_ 前缀的
     * 悬浮窗设置完全隔离）。</p>
     */
    public static final String PREF_THEME_SEED = "hulfix_theme_seed";

    /** 预设种子色主题叠加层（索引与设置页色板一致；0 号位占位 = 动态取色）。 */
    private static final int[] SEED_OVERLAYS = {
            0, // 0 = 动态取色：不覆盖主题，交由 DynamicColors 处理
            R.style.ThemeOverlay_HULFix_Seed1,
            R.style.ThemeOverlay_HULFix_Seed2,
            R.style.ThemeOverlay_HULFix_Seed3,
            R.style.ThemeOverlay_HULFix_Seed4,
            R.style.ThemeOverlay_HULFix_Seed5,
            R.style.ThemeOverlay_HULFix_Seed6,
            R.style.ThemeOverlay_HULFix_Seed7,
            R.style.ThemeOverlay_HULFix_Seed8,
    };

    /** Fragment 标签前缀（Activity 重建后按标签找回 FragmentManager 托管的实例）。 */
    private static final String TAG_PREFIX = "hulfix_page_";
    private static final String STATE_CURRENT = "hulfix_current_page";

    private final Fragment[] mFragments = {
            new StyleFragment(), new AnimFragment(), new PositionFragment(),
            new AppsFragment(), new EngineFragment()
    };

    private BottomNavigationView mBottomNav;
    private NavigationRailView mNavRail;
    private int mCurrent = -1;

    @Override
    protected void onStart() {
        super.onStart();
        // App 每次回到前台都主动同步一次全量设置：覆盖"SystemUI 在 App 之前
        // 重启、请求广播时序竞争"的残余窗口（App 在前台 = 请求必然被响应）
        SettingsStore.get(this).notifySystemUiNow();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsStore.init(this);
        applyAppTheme();
        setContentView(R.layout.activity_settings);

        mBottomNav = findViewById(R.id.bottom_nav);
        mNavRail = findViewById(R.id.nav_rail);

        // 主题为 NoActionBar，需手动把工具栏挂载为 ActionBar 以驱动标题切换
        setSupportActionBar(findViewById(R.id.toolbar));

        // 平板/折叠屏自适应：按最短边判定（避免横屏手机被误判为平板）
        boolean tablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        mNavRail.setVisibility(tablet ? View.VISIBLE : View.GONE);
        mBottomNav.setVisibility(tablet ? View.GONE : View.VISIBLE);

        mBottomNav.setOnItemSelectedListener(item -> onNavSelected(item.getItemId()));
        mNavRail.setOnItemSelectedListener(item -> onNavSelected(item.getItemId()));

        if (savedInstanceState == null) {
            selectPage(0);
        } else {
            // Activity 重建（进程重建 / 旋屏）：FragmentManager 已按标签恢复旧实例，
            // 必须换回托管实例再操作——show() 一个从未添加过的新实例会抛异常。
            for (int i = 0; i < mFragments.length; i++) {
                Fragment existing = getSupportFragmentManager().findFragmentByTag(TAG_PREFIX + i);
                if (existing != null) mFragments[i] = existing;
            }
            mCurrent = savedInstanceState.getInt(STATE_CURRENT, 0);
            syncNavAndTitle();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_CURRENT, mCurrent);
    }

    private boolean onNavSelected(int itemId) {
        for (int i = 0; i < NAV_IDS.length; i++) {
            if (NAV_IDS[i] == itemId) {
                selectPage(i);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onConfigurationChanged(@NonNull android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // configChanges 拦截了 uiMode（深/浅色切换）与屏幕尺寸变化：手动刷新当前页，
        // 保证预览控件与导航形态跟随配置立即更新
        boolean tablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        mNavRail.setVisibility(tablet ? View.VISIBLE : View.GONE);
        mBottomNav.setVisibility(tablet ? View.GONE : View.VISIBLE);
        // 深/浅色切换时重新应用主题叠加层：让随后新建的视图/对话框拿到
        // values-night 变体的色调（已存在的视图保持原色，与基础主题行为一致）
        reapplyAppTheme();
        if (mCurrent >= 0 && mCurrent < mFragments.length) {
            Fragment f = mFragments[mCurrent];
            if (f instanceof BaseSettingsFragment) {
                ((BaseSettingsFragment) f).refresh();
            }
        }
    }

    /**
     * 切换页面：add 一次 + show/hide。
     *
     * <p>首次进入某页面才 {@code add}；此后切换只是显隐切换，视图树完整保留，
     * 切页成本 = 一次 90/210ms 的 alpha 转场，无重建、无重新编译着色器。</p>
     */
    private void selectPage(int index) {
        if (index == mCurrent) return;
        int prev = mCurrent;
        mCurrent = index;
        FragmentTransaction ft = getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.nav_enter, R.anim.nav_exit);
        if (prev >= 0 && mFragments[prev].isAdded()) {
            ft.hide(mFragments[prev]);
        }
        if (mFragments[index].isAdded()) {
            ft.show(mFragments[index]);
        } else {
            ft.add(R.id.fragment_container, mFragments[index], TAG_PREFIX + index);
        }
        ft.commit();
        syncNavAndTitle();
    }

    private void syncNavAndTitle() {
        if (mCurrent < 0 || mCurrent >= NAV_IDS.length) return;
        // 同步两个导航的选中态
        mBottomNav.getMenu().findItem(NAV_IDS[mCurrent]).setChecked(true);
        mNavRail.getMenu().findItem(NAV_IDS[mCurrent]).setChecked(true);

        // 标题反映当前页
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(TITLE_IDS[mCurrent]));
        }
    }

    // ==================== 主题色 ====================

    /**
     * 应用用户选择的主题色（在 setContentView 之前调用，保证视图读取到新主题）。
     *
     * <p>两条路径：</p>
     * <ul>
     *   <li>预设种子色：{@code Theme#applyStyle(overlay, true)} 把叠加层强加到
     *       活动主题上，只覆盖强调色角色（colorPrimary / container / secondary 系），
     *       中性背景沿用基础主题——与 M3 种子色机制的思路一致；</li>
     *   <li>跟随壁纸（动态取色）：交给 Material 的
     *       {@code DynamicColors.applyToActivityIfAvailable}，Android 12+ 生效，
     *       低版本自动回退基础主题默认色，零风险。</li>
     * </ul>
     */
    private void applyAppTheme() {
        int seed = SettingsStore.get(this).prefs().getInt(PREF_THEME_SEED, 0);
        if (seed > 0 && seed < SEED_OVERLAYS.length) {
            getTheme().applyStyle(SEED_OVERLAYS[seed], true);
        } else {
            DynamicColors.applyToActivityIfAvailable(this);
        }
    }

    /**
     * 配置变化（深/浅色切换）后重新应用主题。
     *
     * <p>{@code Theme#applyStyle} 对已应用过的样式会直接覆盖同名项，但颜色引用
     * 是在视图读取属性时才按当前配置解析的——重新 apply 一次即可让后续新建的
     * 视图拿到正确的日夜色调。</p>
     */
    private void reapplyAppTheme() {
        int seed = SettingsStore.get(this).prefs().getInt(PREF_THEME_SEED, 0);
        if (seed > 0 && seed < SEED_OVERLAYS.length) {
            getTheme().applyStyle(SEED_OVERLAYS[seed], true);
        }
    }
}
