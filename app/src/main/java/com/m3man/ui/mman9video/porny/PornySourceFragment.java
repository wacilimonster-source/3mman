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
import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

/**
 * 91porny 来源容器：三个列表 Tab（最新 / 高清 / 蝌蚪）。
 * <p>
 * 站点结构（2026-11-28 实测）：
 * <ul>
 *   <li>最新 {@code /?page=N}，48 条/页</li>
 *   <li>高清 {@code /video/category/hd/N}，24 条/页</li>
 *   <li>蝌蚪 {@code /videos?page=N}，24 条/页</li>
 * </ul>
 * 三个列表共用 {@link PornyListFragment}，仅 kind 不同。
 */
public class PornySourceFragment extends Fragment {

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

        List<Fragment> fragments = new ArrayList<>();
        fragments.add(PornyListFragment.newInstance(Parse91PornyVideo.LIST_HOME));
        fragments.add(PornyListFragment.newInstance(Parse91PornyVideo.LIST_HD));
        fragments.add(PornyListFragment.newInstance(Parse91PornyVideo.LIST_TADPOLE));

        final String[] titles = {
                getString(R.string.porny_tab_latest),
                getString(R.string.porny_tab_hd),
                getString(R.string.porny_tab_tadpole)
        };
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
                        return position >= 0 && position < titles.length ? titles[position] : "";
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
