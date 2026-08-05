package com.m3man.ui.mman9video.play;

import com.m3man.data.db.entity.V9MmanItem;

/**
 * @author flymegoc
 * @date 2017/11/27
 * @describe
 */

public interface IPlay extends IBasePlay {
    void loadVideoUrl(V9MmanItem v9MmanItem);

    String getVideoCacheProxyUrl(String originalVideoUrl);

    boolean isUserLogin();

    int getLoginUserId();

    void updateV9MmanItemForHistory(V9MmanItem v9MmanItem);

    V9MmanItem findV9MmanItemByViewKey(String viewKey);

    void setFavoriteNeedRefresh(boolean favoriteNeedRefresh);
}
