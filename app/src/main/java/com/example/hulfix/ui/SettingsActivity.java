package com.example.hulfix.ui;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.example.hulfix.R;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigationrail.NavigationRailView;

/**
 * HULFix 设置主界面。
 *
 * <p><b>平板/折叠屏适配</b>：屏幕最短边 ≥ 600dp 时自动切换为左侧导航栏
 * （NavigationRailView）+ 右侧内容的横向布局；否则使用底部导航
 * （BottomNavigationView）。两套导航共享同一份菜单与 Fragment 集合，
 * 所有页面内容均限宽 640dp 居中，长宽比变化不会导致文字溢出或控件偏移。</p>
 */
public class SettingsActivity extends AppCompatActivity {

    private static final int[] NAV_IDS = {
            R.id.nav_style, R.id.nav_anim, R.id.nav_position, R.id.nav_apps, R.id.nav_engine
    };
    private static final int[] TITLE_IDS = {
            R.string.nav_style, R.string.nav_anim, R.string.nav_position,
            R.string.nav_apps, R.string.nav_engine
    };

    private final Fragment[] mFragments = {
            new StyleFragment(), new AnimFragment(), new PositionFragment(),
            new AppsFragment(), new EngineFragment()
    };

    private BottomNavigationView mBottomNav;
    private NavigationRailView mNavRail;
    private int mCurrent = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsStore.init(this);
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
        }
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
        Fragment f = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (f instanceof BaseSettingsFragment) {
            ((BaseSettingsFragment) f).refresh();
        }
    }

    private void selectPage(int index) {
        if (index == mCurrent) return;
        mCurrent = index;
        getSupportFragmentManager().beginTransaction()
                .setCustomAnimations(R.anim.nav_enter, R.anim.nav_exit,
                        R.anim.nav_enter, R.anim.nav_exit)
                .replace(R.id.fragment_container, mFragments[index])
                .commit();

        // 同步两个导航的选中态
        mBottomNav.getMenu().findItem(NAV_IDS[index]).setChecked(true);
        mNavRail.getMenu().findItem(NAV_IDS[index]).setChecked(true);

        // 标题反映当前页
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(getString(TITLE_IDS[index]));
        }
    }
}
