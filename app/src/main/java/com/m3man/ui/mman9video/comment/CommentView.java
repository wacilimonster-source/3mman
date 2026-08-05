package com.m3man.ui.mman9video.comment;

import com.m3man.data.model.VideoComment;
import com.m3man.ui.BaseView;

import java.util.List;

public interface CommentView extends BaseView{

    void setVideoCommentData(List<VideoComment> videoCommentList, boolean pullToRefresh);

    void setMoreVideoCommentData(List<VideoComment> videoCommentList);

    void noMoreVideoCommentData(String message);

    void loadMoreVideoCommentError(String message);

    void loadVideoCommentError(String message);

    void commentVideoSuccess(String message);

    void commentVideoError(String message);

    void replyVideoCommentSuccess(String message);

    void replyVideoCommentError(String message);
}
