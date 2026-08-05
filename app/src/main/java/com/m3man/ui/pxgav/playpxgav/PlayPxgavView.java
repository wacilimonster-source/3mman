package com.m3man.ui.pxgav.playpxgav;

import com.m3man.data.model.pxgav.PxgavModel;
import com.m3man.data.model.pxgav.PxgavVideoParserJsonResult;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * @author flymegoc
 * @date 2018/1/30
 */

public interface PlayPxgavView extends BaseView {
    void playVideo(PxgavVideoParserJsonResult pxgavVideoParserJsonResult);

    void listVideo(List<PxgavModel> pxgavModelList);
}
