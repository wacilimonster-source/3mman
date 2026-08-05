package com.m3man.ui.update;

import com.m3man.data.model.UpdateVersion;
import com.m3man.ui.BaseView;

/**
 * @author flymegoc
 * @date 2017/12/22
 */

public interface UpdateView extends BaseView {
    void needUpdate(UpdateVersion updateVersion);

    void noNeedUpdate();

    void checkUpdateError(String message);
}
