package cn.jiguang.demo.view;


import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;

import java.util.List;

/**
 * Create by wangqingqing
 * On 2018/5/16 17:32
 * Copyright(c) 2018 极光
 * Description
 */
public class BaseFragmentPagerAdapter extends FragmentPagerAdapter {
    private List<String> titles;
    private List<Fragment> fragments;

    public BaseFragmentPagerAdapter(FragmentManager fm, List<String> titles, List<Fragment> fragments) {
        super(fm);
        this.titles = titles;
        this.fragments = fragments;
    }

    @Override
    public Fragment getItem(int arg0) {
        return fragments.get(arg0);
    }

    @Override
    public CharSequence getPageTitle(int position) {
        return titles == null ? null : titles.get(position);
    }

    @Override
    public int getCount() {
        return fragments.size();
    }
}
