package com.m3man.ui.kedouwo.play;

import com.m3man.data.model.kedouwo.KeDouRelated;
import com.m3man.ui.BaseView;

/**
 * Created by alex
 * Des:
 * Date: 2019/8/28.
 */
public interface KeDouPlayView extends BaseView {

    void onVideoRelated(KeDouRelated keDouRelated);

    void onVideoRelatedError(String msg);

    void onVideoUrl(String url);
}
