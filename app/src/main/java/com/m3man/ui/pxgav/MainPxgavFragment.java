package com.m3man.ui.pxgav;

import com.m3man.data.db.entity.Category;
import com.m3man.ui.basemain.BaseMainFragment;

/**
 * @author flymegoc
 * @date 2018/1/29
 */

public class MainPxgavFragment extends BaseMainFragment {

    public static MainPxgavFragment getInstance() {
        return new MainPxgavFragment();
    }

    @Override
    public int getCategoryType() {
        return Category.TYPE_PXG_AV;
    }

    @Override
    public boolean isNeedDestroy() {
        return true;
    }
}
