package com.m3man.utils;

import android.text.TextUtils;

import com.m3man.data.model.User;

/**
 * 用户帮助
 *
 * @author flymegoc
 * @date 2017/12/29
 */

public class UserHelper {
    /**
     * 随机生成10位机器指纹
     *
     * @return 指纹码
     */
    public static String randomFingerprint() {
        String keys = "0123456789";
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < keys.length(); i++) {
            int pos = (int) (Math.random() * keys.length());
            pos = (int) Math.floor(pos);
            key.append(keys.charAt(pos));
        }
        return key.toString();
    }

    /**
     * 随机生成4位验证码
     *
     * @return 4位验证码
     */
    public static String randomCaptcha() {
        String keys = "0123456789";
        int length = 4;
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int pos = (int) (Math.random() * keys.length());
            pos = (int) Math.floor(pos);
            key.append(keys.charAt(pos));
        }
        return key.toString();
    }

    /**
     * 随机生成32位机器指纹
     *
     * @return 指纹码
     */
    public static String randomFingerprint2() {
        String keys = "abcdefghijklmnopqrstuvwxyz0123456789";
        int keyLength = 32;
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < keyLength; i++) {
            int pos = (int) (Math.random() * keys.length());
            pos = (int) Math.floor(pos);
            key.append(keys.charAt(pos));
        }
        return key.toString();
    }

    /**
     * 当前是否已经登录了
     * @param user
     * @return
     */
    public static boolean isUserInfoComplete(User user) {
        //2018年3月31日 因为登陆后后台已经没有返回uid，无法获取，去掉条件uid>0的条件，但uid可以在收藏时候获取
        return user != null && !TextUtils.isEmpty(user.getUserName());
    }

    /**
     * 判定登录 POST 的响应页是否代表登录成功。
     * <p>
     * 旧实现 {@code (!含"登录" || !含"注册" || 含"退出")} 过弱：
     * 错误页只要不同时含「登录」「注册」文案就会被误判为成功，
     * 随后 parseUserInfo 解析错误页产出残缺用户。
     * 现改为要求正向证据：
     * <ul>
     *   <li>页面出现「退出」入口（登录后才有）→ 成功；</li>
     *   <li>同时保留「登录」「注册」入口 → 仍是未登录表单页 → 失败；</li>
     *   <li>空页 / 无法确证登录态 → 保守判为失败（宁可让用户重试，也不把错误页当成功）。</li>
     * </ul>
     */
    public static boolean isMmanVideoLoginSuccess(String html) {
        if (html == null || html.isEmpty()) {
            return false;
        }
        // 正向证据任一即可：退出入口 / 登录成功页专有的用户信息区块（与
        // ParseV9MmanVideo.parseUserInfo 解析的目标元素一致）/ 最近登录时间文案
        if (html.contains("退出")
                || html.contains("userinfo-content")
                || html.contains("最后登录")) {
            return true;
        }
        return false;
    }
}
