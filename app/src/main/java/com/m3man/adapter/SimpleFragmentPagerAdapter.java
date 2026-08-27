package com.m3man.adapter;

import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;

import com.m3man.ui.BaseFragment;

import java.util.List;

import javax.inject.Inject;

/**
 * 通用 FragmentPagerAdapter（下载页/播放页通用）。
 * <p>
 * 合并原 DownloadFragmentAdapter 与 PlayFragmentAdapter，消除 100% 重复代码。
 */
public class SimpleFragmentPagerAdapter extends FragmentPagerAdapter {

    private List<Fragment> fragmentList;

    @Inject
    public SimpleFragmentPagerAdapter(FragmentManager fm) {
        super(fm);
    }

    public void setData(List<Fragment> fragmentList) {
        this.fragmentList = fragmentList;
    }

    @Override
    public Fragment getItem(int position) {
        return fragmentList.get(position);
    }

    @Override
    public int getCount() {
        return fragmentList == null ? 0 : fragmentList.size();
    }

    @Override
    public CharSequence getPageTitle(int position) {
        return fragmentList == null ? "" : ((BaseFragment) fragmentList.get(position)).getTitle();
    }
}
