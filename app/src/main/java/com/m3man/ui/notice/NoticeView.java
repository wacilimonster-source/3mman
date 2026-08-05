package com.m3man.ui.notice;

import com.m3man.data.model.Notice;
import com.m3man.ui.update.UpdateView;

/**
 * @author flymegoc
 * @date 2018/1/26
 */

public interface NoticeView extends UpdateView {
    void haveNewNotice(Notice notice);

    void noNewNotice();

    void checkNewNoticeError(String message);
}
