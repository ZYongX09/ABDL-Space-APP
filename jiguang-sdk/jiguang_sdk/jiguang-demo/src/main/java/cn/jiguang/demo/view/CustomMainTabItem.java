package cn.jiguang.demo.view;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.android.material.tabs.TabLayout;

import java.util.List;

import cn.jiguang.demo.R;


public class CustomMainTabItem {

    private List<Integer> mTitles ;

    private List<Integer> sDrawable ;


    private TabLayout mTabLayout;
    private Context mContext;

    private CustomMainTabItem(){

    }
    //返回CustomBotTabItem实例
    public static CustomMainTabItem create() {
        return TabItemHolder.sCustomTabItem;
    }

    public CustomMainTabItem setTitleList(List<Integer> mTitles) {
        this.mTitles=mTitles;
        return this;
    }

    public CustomMainTabItem setIconList(List<Integer> sDrawable) {
        this.sDrawable=sDrawable;
        return this;
    }

    //创建CustomBotTabItem实例
    private static class TabItemHolder {
        private static CustomMainTabItem sCustomTabItem = new CustomMainTabItem();
    }

    //引入布局需要的Context
    public CustomMainTabItem setContext(Context context) {
        mContext = context.getApplicationContext();
        return this;
    }

    //需要自定义的TabLayout
    public CustomMainTabItem setTabLayout(TabLayout tabLayout) {
        mTabLayout = tabLayout;
        return this;
    }


    //创建Tab
    public CustomMainTabItem build() {
        initTabLayout();
        return this;
    }

    //初始化Tab
    private void initTabLayout() {
        mTabLayout.removeAllTabs();
        for (int i = 0; i < mTitles.size(); i++) {
            TabLayout.Tab tab = mTabLayout.newTab();
            tab.setCustomView(getTabView(i));
            tab.setText(mTitles.get(i));
            mTabLayout.addTab(tab);
        }
    }

    //自定义Tab样式
    private View getTabView(final int position) {
        final View view = LayoutInflater.from(mContext).inflate(R.layout.d_tab_layout, null);
        TextView tvTitle = view.findViewById(R.id.tv);
        ImageView ivTitle = view.findViewById(R.id.iv);
        ivTitle.setImageResource(sDrawable.get(position));
        tvTitle.setText(mContext.getString(mTitles.get(position)));

        tvTitle.setSelected(position == 0);
        ivTitle.setSelected(position == 0);
        return view;
    }

}
