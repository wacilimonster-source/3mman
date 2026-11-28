package com.m3man.ui.mman9video.porny;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager.widget.ViewPager;

import com.google.android.material.tabs.TabLayout;
import com.m3man.R;
import com.m3man.parser.Parse91PornyVideo;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 91porny 来源容器：Tab 与站点 /video 页的分类菜单一一对应。
 * <p>
 * 站点结构（2026-11-28 实测 14 个分类，菜单在 /video 页顶部）：
 * <pre>
 *   最新（首页 /，48 条/页）
 *   最近更新 latest        高清视频 hd          最近加精 recent-favorite
 *   当前最热 hot-list      最近得分 recent-rating 非付费   nonpaid
 *   91原创  ori            10分钟以上 long-list   20分钟以上 longer-list
 *   本月讨论 month-discuss 本月收藏 top-favorite   收藏最多 most-favorite
 *   本月最热 top-list      上月最热 top-last      蝌蚪     videos（/videos）
 * </pre>
 * 每个 Tab 只加载第一页，滚动到底再翻页（{@link PornyListFragment} 负责分页）。
 */
public class PornySourceFragment extends Fragment {

    /** 分类 slug 与文案资源一一对应；slug 为空串表示站点首页「最新」。 */
    private static final String[] SLUGS = {
            "",
            "latest",
            "hd",
            "recent-favorite",
            "hot-list",
            "recent-rating",
            "nonpaid",
            "ori",
            "long-list",
            "longer-list",
            "month-discuss",
            "top-favorite",
            "most-favorite",
            "top-list",
            "top-last",
            Parse91PornyVideo.SLUG_TADPOLE
    };

    private static final int[] TITLES = {
            R.string.porny_tab_latest,
            R.string.porny_tab_latest_update,
            R.string.porny_tab_hd,
            R.string.porny_tab_recent_favorite,
            R.string.porny_tab_hot,
            R.string.porny_tab_recent_rating,
            R.string.porny_tab_nonpaid,
            R.string.porny_tab_original,
            R.string.porny_tab_long,
            R.string.porny_tab_longer,
            R.string.porny_tab_month_discuss,
            R.string.porny_tab_month_favorite,
            R.string.porny_tab_most_favorite,
            R.string.porny_tab_month_hot,
            R.string.porny_tab_last_month_hot,
            R.string.porny_tab_tadpole
    };

    private TabLayout tabLayout;
    private ViewPager viewPager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_porny_source, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        tabLayout = view.findViewById(R.id.tab_layout_porny);
        viewPager = view.findViewById(R.id.view_pager_porny);

        final List<Fragment> fragments = new ArrayList<>();
        final List<String> titles = new ArrayList<>();
        for (int i = 0; i < SLUGS.length; i++) {
            fragments.add(PornyListFragment.newInstance(SLUGS[i]));
            titles.add(getString(TITLES[i]));
        }

        // 不用 SimpleFragmentPagerAdapter：它会把页面强转成 BaseFragment（DaggerFragment），
        // 这里的列表页是普通 Fragment，强转会 ClassCastException。
        androidx.fragment.app.FragmentPagerAdapter adapter =
                new androidx.fragment.app.FragmentPagerAdapter(getChildFragmentManager(),
                        androidx.fragment.app.FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {
                    @NonNull
                    @Override
                    public Fragment getItem(int position) {
                        return fragments.get(position);
                    }

                    @Override
                    public int getCount() {
                        return fragments.size();
                    }

                    @Nullable
                    @Override
                    public CharSequence getPageTitle(int position) {
                        return position >= 0 && position < titles.size() ? titles.get(position) : "";
                    }
                };
        viewPager.setAdapter(adapter);
        viewPager.setOffscreenPageLimit(1);
        tabLayout.setupWithViewPager(viewPager);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        tabLayout = null;
        viewPager = null;
    }
}
