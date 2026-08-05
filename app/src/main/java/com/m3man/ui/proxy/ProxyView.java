package com.m3man.ui.proxy;

import com.m3man.data.model.ProxyModel;
import com.m3man.ui.BaseView;

import java.util.List;

/**
 * @author flymegoc
 * @date 2018/1/20
 */

public interface ProxyView extends BaseView {
    void testProxySuccess(String message);

    void testProxyError(String message);

    void parseXiCiDaiLiSuccess(List<ProxyModel> proxyModelList);

    void loadMoreDataComplete();

    void loadMoreFailed();

    void noMoreData();

    void setMoreData(List<ProxyModel> proxyModelList);

    void beginParseProxy();
}
