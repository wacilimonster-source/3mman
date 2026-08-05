package com.m3man.ui.kedouwo;

import com.m3man.data.db.entity.Category;
import com.m3man.ui.basemain.BaseMainFragment;

/**
 * Created by alex
 * Des:
 * Date: 2019/8/27.
 */
public class MainKeDouFragment extends BaseMainFragment {
    @Override
    public int getCategoryType() {
        return Category.TYPE_KE_DOU_WO;
    }

    public static MainKeDouFragment getInstance() {
        return new MainKeDouFragment();
    }
}
