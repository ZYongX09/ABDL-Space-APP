package cn.jiguang.demo;

import android.Manifest;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.List;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager.widget.ViewPager;

import cn.jiguang.demo.view.BaseFragmentPagerAdapter;
import cn.jiguang.demo.baselibrary.ScreenUtils;
import cn.jiguang.demo.view.CustomMainTabItem;

public class MainActivity extends AppCompatActivity {

    private TabLayout tabLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.d_activity_main);
        initView();
        ScreenUtils.setStatusBarTransparent(getWindow());
        requestPermissions();
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 100);
        }
    }

    private void initView() {
        tabLayout = findViewById(R.id.tabLayout);
        final ViewPager viewPager = findViewById(R.id.viewPager);
        createTab();
        ArrayList<Fragment> fragments = new ArrayList<>();
        fragments.add(new HomeFragment());
        fragments.add(new AppInfoFragment());
        viewPager.setAdapter(new BaseFragmentPagerAdapter(getSupportFragmentManager(),null,fragments));

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                int position = tab.getPosition();
                if (position == 1) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        int systemUiVisibility = getWindow().getDecorView().getSystemUiVisibility() | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        getWindow().getDecorView().setSystemUiVisibility(systemUiVisibility);
                    }
                } else {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        int systemUiVisibility = getWindow().getDecorView().getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                        getWindow().getDecorView().setSystemUiVisibility(systemUiVisibility);
                    }
                }

                viewPager.setCurrentItem(position,false);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {

            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {

            }
        });
    }


    private void createTab(){

        List<Integer> mTitles = new ArrayList<>();
        List<Integer> sDrawable = new ArrayList<>();

        mTitles.add(R.string.tab_main);
        mTitles.add(R.string.tab_info);

        sDrawable.add(R.drawable.d_tab_main_selector);
        sDrawable.add(R.drawable.d_tab_info_selector);

        CustomMainTabItem.create()
                .setContext(this)
                .setTabLayout(tabLayout)
                .setTitleList(mTitles)
                .setIconList(sDrawable)
                .build();
    }
}