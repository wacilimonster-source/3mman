package com.m3man.ui.mman9video.author;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 *
 * @author flymegoc
 * @date 2018/1/8
 */

public interface AuthorView extends BaseView{
    void loadMoreDataComplete();

    void loadMoreFailed();

    void noMoreData();

    void setMoreData(List<V9MmanItem> v9MmanItemList);

    void setData(List<V9MmanItem> data);

    /** 作者 UID 自愈成功后同步页面后续请求使用的新 UID。 */
    void onAuthorUidHealed(String newUid);
}
