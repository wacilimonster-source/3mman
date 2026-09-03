package com.m3man.ui.mman9video.comment;


import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatEditText;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.orhanobut.logger.Logger;
import com.sdsmdg.tastytoast.TastyToast;
import com.m3man.R;
import com.m3man.adapter.VideoCommentAdapter;
import com.m3man.constants.Keys;
import com.m3man.constants.KeysActivityRequestResultCode;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.db.entity.VideoResult;
import com.m3man.data.model.VideoComment;
import com.m3man.ui.MvpFragment;
import com.m3man.ui.mman9video.user.UserLoginActivity;
import com.m3man.utils.AppUtils;
import com.m3man.utils.DialogUtils;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;

/**
 * A simple {@link Fragment} subclass.
 * 视频评论
 *
 * @author megoc
 */
public class CommentFragment extends MvpFragment<CommentView, CommentPresenter> implements CommentView, SwipeRefreshLayout.OnRefreshListener {

    private static final String TAG = CommentFragment.class.getSimpleName();
    @BindView(R.id.recyclerView_video_comment)
    RecyclerView recyclerViewVideoComment;
    @BindView(R.id.comment_swipe_refreshLayout)
    SwipeRefreshLayout commentSwipeRefreshLayout;
    @BindView(R.id.et_comment_video)
    AppCompatEditText etCommentVideo;
    @BindView(R.id.iv_send_video_comment)
    ImageView ivSendVideoComment;
    Unbinder unbinder;

    private VideoCommentAdapter videoCommentAdapter;

    private boolean isComment = true;
    private VideoComment videoComment;

    private AlertDialog commentVideoDialog;

    private V9MmanItem v9MmanItem;

    @Inject
    protected CommentPresenter commentPresenter;
    /**
     * 延迟加载评论
     */
    private boolean delayLoadComment;
    private ArrayList<VideoComment> videoCommentList;

    private DividerItemDecoration dividerItemDecoration;

    @Inject
    public CommentFragment() {
        // Required empty public constructor
        Logger.t(TAG).d("CommentFragment初始化了.....");
        videoCommentList = new ArrayList<>();
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        dividerItemDecoration = new DividerItemDecoration(activity, DividerItemDecoration.VERTICAL);
        videoCommentAdapter = new VideoCommentAdapter(getContext(), R.layout.item_video_comment, videoCommentList);
        videoCommentAdapter.setOnLoadMoreListener(new BaseQuickAdapter.RequestLoadMoreListener() {
            @Override
            public void onLoadMoreRequested() {
                //加载评论
                String videoId = getSafeVideoId();
                if (videoId == null || presenter == null) {
                    videoCommentAdapter.loadMoreFail();
                    return;
                }
                presenter.loadVideoComment(videoId, v9MmanItem.getViewKey(), false);
            }
        }, recyclerViewVideoComment);
        videoCommentAdapter.setOnItemClickListener(new BaseQuickAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(BaseQuickAdapter adapter, View view, int position) {
                isComment = false;
                videoCommentAdapter.setClickPosition(position);
                videoCommentAdapter.notifyDataSetChanged();
                videoComment = (VideoComment) adapter.getData().get(position);
                etCommentVideo.setHint("回复：" + videoComment.getuName());
            }
        });
        commentVideoDialog = DialogUtils.initLoadingDialog(getContext(), "提交评论中,请稍后...");
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_comment, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        unbinder = ButterKnife.bind(this, view);
        initVideoComments();
        initListener();
    }

    @Override
    protected void onLazyLoadOnce() {
        super.onLazyLoadOnce();
        if (delayLoadComment && presenter != null) {
            String videoId = getSafeVideoId();
            if (videoId == null) {
                //视频还未解析成功，保持延迟标记，等待解析完成后再加载
                return;
            }
            delayLoadComment = false;
            presenter.loadVideoComment(videoId, v9MmanItem.getViewKey(), true);
        }
    }

    @Override
    public String getTitle() {
        return "评论";
    }


    private void cleanVideoCommentInput() {
        etCommentVideo.setText("");
    }

    private void initVideoComments() {
        recyclerViewVideoComment.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerViewVideoComment.addItemDecoration(dividerItemDecoration);
        recyclerViewVideoComment.setAdapter(videoCommentAdapter);
    }

    private void initListener() {
        commentSwipeRefreshLayout.setEnabled(false);
        AppUtils.setColorSchemeColors(getContext(), commentSwipeRefreshLayout);
        commentSwipeRefreshLayout.setOnRefreshListener(this);
        ivSendVideoComment.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String comment = etCommentVideo.getText().toString().trim();
                commentOrReplyVideo(comment);
            }
        });
    }

    public void setV9MmanItem(V9MmanItem v9MmanItem) {
        this.v9MmanItem = v9MmanItem;
    }

    /**
     * 安全获取当前视频的videoId。
     * 视频尚未解析、item为空、关联对象已被删除等情况均返回null，避免空指针崩溃。
     *
     * @return videoId，不可用时返回null
     */
    private String getSafeVideoId() {
        return getSafeVideoId(v9MmanItem);
    }

    /**
     * 安全获取指定item的videoId
     *
     * @param item 视频item
     * @return videoId，不可用时返回null
     */
    private String getSafeVideoId(V9MmanItem item) {
        if (item == null || item.getVideoResultId() == 0) {
            return null;
        }
        try {
            VideoResult videoResult = item.getVideoResult();
            if (videoResult == null || TextUtils.isEmpty(videoResult.getVideoId())) {
                return null;
            }
            return videoResult.getVideoId();
        } catch (Exception e) {
            Logger.t(TAG).e(e, "获取视频信息失败");
            return null;
        }
    }

    /**
     * 评论视频或者回复评论
     *
     * @param comment 留言内容
     */
    private synchronized void commentOrReplyVideo(String comment) {
        Logger.t(TAG).d("评论视频或者回复评论....");
        if (TextUtils.isEmpty(comment)) {
            showMessage("请填写评论", TastyToast.INFO);
            return;
        }

        if (!presenter.isUserLogin()) {
            showMessage("请先登录帐号", TastyToast.INFO);
            goToLogin(KeysActivityRequestResultCode.LOGIN_ACTION_FOR_GET_UID);
            return;
        }
        String vid = getSafeVideoId();
        if (vid == null) {
            showMessage("视频地址还未解析成功，无法评论", TastyToast.INFO);
            return;
        }
        String uid = String.valueOf(presenter.getLoginUserId());
        if (isComment) {
            commentVideoDialog.show();
            presenter.commentVideo(comment, uid, vid, v9MmanItem.getViewKey());
        } else {
            if (videoComment == null) {
                showMessage("请先选择需要回复的评论！", TastyToast.INFO);
                return;
            }
            commentVideoDialog.show();
            String username = videoComment.getuName();
            String commentId = videoComment.getReplyId();
            presenter.replyComment(comment, username, vid, commentId, v9MmanItem.getViewKey());
        }
    }

    /**
     * 去登录
     *
     * @param actionKey 登录之后的动作key
     */
    private void goToLogin(int actionKey) {
        Intent intent = new Intent(getContext(), UserLoginActivity.class);
        intent.putExtra(Keys.KEY_INTENT_LOGIN_FOR_ACTION, actionKey);
        startActivityForResultWithAnimation(intent, 0);
    }

    @Override
    public void setVideoCommentData(List<VideoComment> videoCommentList, boolean pullToRefresh) {
        if (pullToRefresh) {
            recyclerViewVideoComment.smoothScrollToPosition(0);
        }
        videoCommentAdapter.setNewData(videoCommentList);
        commentSwipeRefreshLayout.setEnabled(true);
    }

    @Override
    public void setMoreVideoCommentData(List<VideoComment> videoCommentList) {
        videoCommentAdapter.loadMoreComplete();
        videoCommentAdapter.addData(videoCommentList);
    }

    @Override
    public void noMoreVideoCommentData(String message) {
        videoCommentAdapter.loadMoreEnd(true);
        //showMessage(message, TastyToast.INFO);
    }

    @Override
    public void loadMoreVideoCommentError(String message) {
        videoCommentAdapter.loadMoreFail();
    }

    @Override
    public void loadVideoCommentError(String message) {
        showMessage("加载评论失败了，点击重试", TastyToast.ERROR);
    }

    @Override
    public void commentVideoSuccess(String message) {
        cleanVideoCommentInput();
        reFreshData(v9MmanItem);
        showMessage(message, TastyToast.SUCCESS);
    }

    @Override
    public void commentVideoError(String message) {
        showMessage(message, TastyToast.ERROR);
    }

    @Override
    public void replyVideoCommentSuccess(String message) {
        cleanVideoCommentInput();
        isComment = true;
        etCommentVideo.setHint(R.string.comment_video_hint_tip);
        videoCommentAdapter.setClickPosition(-1);
        reFreshData(v9MmanItem);
        showMessage(message, TastyToast.SUCCESS);
    }

    private void reFreshData(V9MmanItem v9MmanItem) {
        String videoId = getSafeVideoId(v9MmanItem);
        if (videoId == null || presenter == null) {
            return;
        }
        //刷新
        commentSwipeRefreshLayout.setRefreshing(true);
        presenter.loadVideoComment(videoId, v9MmanItem.getViewKey(), true);
    }

    @Override
    public void replyVideoCommentError(String message) {
        showMessage(message, TastyToast.ERROR);
        dismissDialog();
    }

    @NonNull
    @Override
    public CommentPresenter createPresenter() {
        Logger.t(TAG).d("createPresenter初始化了.....");
        return commentPresenter;
    }

    @Override
    public void showLoading(boolean pullToRefresh) {
        commentSwipeRefreshLayout.setRefreshing(true);
    }

    @Override
    public void showContent() {
        commentSwipeRefreshLayout.setRefreshing(false);
        dismissDialog();
    }

    @Override
    public void showMessage(String msg, int type) {
        super.showMessage(msg, type);
    }

    @Override
    public void showError(String message) {
        commentSwipeRefreshLayout.setRefreshing(false);
        dismissDialog();
    }

    private void dismissDialog() {
        if (commentVideoDialog != null && commentVideoDialog.isShowing()) {
            commentVideoDialog.dismiss();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        //避免Activity销毁时Dialog未关闭导致的WindowLeaked
        dismissDialog();
        if (unbinder != null) {
            unbinder.unbind();
            unbinder = null;
        }
    }

    public void loadVideoComment(String videoId, String viewKey, boolean pullToRefresh) {
        if (presenter != null) {
            presenter.loadVideoComment(videoId, viewKey, pullToRefresh);
        } else {
            delayLoadComment = true;
        }
    }

    @Override
    public void onRefresh() {
        String videoId = getSafeVideoId();
        if (videoId == null || presenter == null) {
            commentSwipeRefreshLayout.setRefreshing(false);
            return;
        }
        presenter.loadVideoComment(videoId, v9MmanItem.getViewKey(), true);
    }
}
