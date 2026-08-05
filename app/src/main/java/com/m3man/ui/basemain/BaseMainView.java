package com.m3man.ui.basemain;

import com.m3man.data.db.entity.Category;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 *
 * @author flymegoc
 * @date 2018/1/25
 */

public interface BaseMainView extends BaseView{
    void onLoadCategoryData(List<Category> categoryList);
    void onLoadAllCategoryData(List<Category> categoryList);
}
