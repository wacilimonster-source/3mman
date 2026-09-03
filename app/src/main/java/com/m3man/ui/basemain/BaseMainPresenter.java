package com.m3man.ui.basemain;

import androidx.annotation.NonNull;

import com.hannesdorfmann.mosby3.mvp.MvpBasePresenter;
import com.m3man.data.DataManager;
import com.m3man.data.db.entity.Category;

import java.util.List;
import java.util.Map;

import javax.inject.Inject;

/**
 * @author flymegoc
 * @date 2018/1/25
 */
public class BaseMainPresenter extends MvpBasePresenter<BaseMainView> implements IBaseMain {
    protected DataManager dataManager;

    @Inject
    public BaseMainPresenter(DataManager dataManager) {
        this.dataManager = dataManager;
    }

    @Override
    public void loadAllCategoryData(int type) {
        final List<Category> categoryList = dataManager.loadAllCategoryDataByType(type);
        ifViewAttached(new ViewAction<BaseMainView>() {
            @Override
            public void run(@NonNull BaseMainView view) {
                view.onLoadAllCategoryData(categoryList);
            }
        });
    }

    @Override
    public void loadCategoryData(int type) {
        final List<Category> categoryList = dataManager.loadCategoryDataByType(type);
        ifViewAttached(new ViewAction<BaseMainView>() {
            @Override
            public void run(@NonNull BaseMainView view) {
                view.onLoadCategoryData(categoryList);
            }
        });
    }

    @Override
    public Category findCategoryById(Long id) {
        return dataManager.findCategoryById(id);
    }

    /**
     * H-09：批量加载分类并转为 Map，避免循环中逐个查询 DB (N+1 问题)
     */
    public Map<Long, Category> loadCategoryMap(int type) {
        List<Category> categoryList = dataManager.loadCategoryDataByType(type);
        Map<Long, Category> map = new java.util.HashMap<>();
        for (Category c : categoryList) {
            map.put(c.getId(), c);
        }
        return map;
    }

    @Override
    public void updateCategoryData(List<Category> categoryList) {
        dataManager.updateCategoryData(categoryList);
    }
}
