package com.m3man.ui.mman9video.play;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.model.VideoComment;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * @author flymegoc
 * @date 2017/11/15
 * @describe
 */

public interface PlayVideoView extends BaseView {
    void showParsingDialog();

    void parseVideoUrlSuccess(V9MmanItem v9MmanItem);

    void errorParseVideoUrl(String errorMessage);

    void favoriteSuccess();
}
