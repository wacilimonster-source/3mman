package com.m3man.ui.mman9video.favorite;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * @author flymegoc
 * @date 2017/11/25
 * @describe
 */

public interface FavoriteView extends BaseView {
    void setFavoriteData(List<V9MmanItem> v9MmanItemList);

    void loadMoreDataComplete();

    void loadMoreFailed();

    void noMoreData();

    void setMoreData(List<V9MmanItem> v9MmanItemList);

    void deleteFavoriteSucc(String message);
    void deleteFavoriteError(String message);
    void showDeleteDialog();
}
