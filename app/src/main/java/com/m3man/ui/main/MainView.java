package com.m3man.ui.main;

import com.m3man.ui.notice.NoticeView;

/**
 * 取消开屏页后，主界面同时承担版本更新与公告检查的回调。
 * NoticeView 继承自 UpdateView，两者的回调都在这里。
 *
 * @author flymegoc
 * @date 2017/12/23
 */

public interface MainView extends NoticeView {
}
