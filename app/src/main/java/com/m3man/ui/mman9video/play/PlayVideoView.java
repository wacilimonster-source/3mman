package com.m3man.ui.mman9video.play;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.BaseView;

/**
 * @author flymegoc
 * @date 2017/11/15
 * @describe
 */

public interface PlayVideoView extends BaseView {
    void showParsingDialog();

    void parseVideoUrlSuccess(V9MmanItem v9MmanItem);

    void playSavedVideo(V9MmanItem item, String mediaUri);

    void play441447Video(V9MmanItem item, String url);

    void errorParseVideoUrl(String errorMessage);

    /**
     * 源站只对登录用户开放该视频（游客打开新视频时页面没有播放器）。
     * 与解析失败区分，页面据此引导登录并在登录成功后重新解析。
     */
    void needLoginForPlayback(String message);

    /**
     * 账号邮箱未验证：站点把播放页重定向到 confirm_email.php，无法取流。
     * 页面据此提示，并给出「去站点验证邮箱」的入口。
     */
    void needEmailVerifyForPlayback(String message, String verifyUrl);

    void favoriteSuccess();
}
