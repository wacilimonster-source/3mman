package com.m3man.ui.download;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.mman9video.favorite.IBaseFavorite;

/**
 * @author flymegoc
 * @date 2017/11/26
 * @describe
 */

public interface IBaseDownload extends IBaseFavorite{
    void downloadVideo(V9MmanItem v9MmanItem, boolean isForceReDownload);
}
