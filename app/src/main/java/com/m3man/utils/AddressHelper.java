package com.m3man.utils;

import com.m3man.data.prefs.PreferencesHelper;

import java.util.Random;

/**
 * @author flymegoc
 * @date 2018/1/26
 */

public class AddressHelper {

    private Random mRandom;
    private PreferencesHelper preferencesHelper;

    /**
     * 无需手动初始化,已在di中全局单例
     */
    public AddressHelper(PreferencesHelper preferencesHelper) {
        mRandom = new Random();
        this.preferencesHelper = preferencesHelper;
    }

    /**
     * 获取随机ip地址
     *
     * @return random ip
     */
    public String getRandomIPAddress() {

        return String.valueOf(mRandom.nextInt(255)) + "." + String.valueOf(mRandom.nextInt(255)) + "." + String.valueOf(mRandom.nextInt(255)) + "." + String.valueOf(mRandom.nextInt(255));
    }

    public String getVideo9MmanAddress() {
        return preferencesHelper.getMman9VideoAddress();
    }

    public String getPornyAddress() {
        return preferencesHelper.getPornyAddress();
    }
}
