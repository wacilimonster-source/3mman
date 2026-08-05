package com.m3man.data.network.okhttp;

import android.text.TextUtils;

import com.m3man.utils.AddressHelper;

/**
 * 给每个请求添加对应的referer header，一定程度上能改善超时现象
 *
 * @author flymegoc
 * @date 2018/1/2
 */

public class HeaderUtils {
    /**
     * 来自播放列表的header
     *
     * @param viewKey 视频key
     * @return header
     */
    public static String getPlayVideoReferer(String viewKey, AddressHelper addressHelper) {
        return addressHelper.getVideo9MmanAddress() + "view_video.php?viewkey=" + viewKey;
    }

    /**
     * 来自主页的header
     *
     * @return header
     */
    public static String getIndexHeader(AddressHelper addressHelper) {
        return addressHelper.getVideo9MmanAddress() + "index.php";
    }

    /**
     * 收藏
     *
     * @return header
     */
    public static String getFavHeader(AddressHelper addressHelper) {
        return addressHelper.getVideo9MmanAddress() + "my_favour.php";
    }

    /**
     * 91porny 源专用 Referer：必须指向 91porny 域名本身，
     * 否则播放页/搜索接口可能因 Referer 校验失败而返回异常页面。
     *
     * @return ref
     */
    public static String getPornyHeader(AddressHelper addressHelper) {
        String pornyAddress = addressHelper.getPornyAddress();
        if (TextUtils.isEmpty(pornyAddress)) {
            return pornyAddress;
        }
        // 保证以 / 结尾，拼接成带路径的首页 Referer
        return pornyAddress.endsWith("/") ? pornyAddress + "search" : pornyAddress + "/search";
    }

    /**
     * 获取用户header
     *
     * @param action login or register
     * @return header
     */
    public static String getUserHeader(AddressHelper addressHelper, String action) {
        return addressHelper.getVideo9MmanAddress() + action + ".php";
    }
}
