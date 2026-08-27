package com.m3man.ui.basemain;

import com.m3man.data.db.entity.Category;

import java.util.List;
import java.util.Map;

/**
 * @author flymegoc
 * @date 2018/1/25
 */

public interface IBaseMain {
    void loadAllCategoryData(int categoryType);

    void loadCategoryData(int categoryType);

    Category findCategoryById(Long id);

    Map<Long, Category> loadCategoryMap(int type);

    void updateCategoryData(List<Category> categoryList);
}
