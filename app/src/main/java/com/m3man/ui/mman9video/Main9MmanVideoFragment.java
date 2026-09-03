package com.m3man.ui.mman9video;


import androidx.fragment.app.Fragment;

import com.m3man.data.db.entity.Category;
import com.m3man.ui.basemain.BaseMainFragment;

/**
 * A simple {@link Fragment} subclass.
 *
 * @author flymegoc
 */
public class Main9MmanVideoFragment extends BaseMainFragment {

    @Override
    public int getCategoryType() {
        return Category.TYPE_91PORN;
    }

    public static Main9MmanVideoFragment getInstance() {
        return new Main9MmanVideoFragment();
    }
}
