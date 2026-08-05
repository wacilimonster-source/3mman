package com.m3man.ui.axgle;


import android.support.v4.app.Fragment;

import com.m3man.data.db.entity.Category;
import com.m3man.ui.basemain.BaseMainFragment;

/**
 * A simple {@link Fragment} subclass.
 * @author megoc
 */
public class MainAxgleFragment extends BaseMainFragment {


    @Override
    public int getCategoryType() {
        return Category.TYPE_AXGLE;
    }

    public MainAxgleFragment() {
        // Required empty public constructor
    }

    public static MainAxgleFragment getInstance() {
        return new MainAxgleFragment();
    }
}
