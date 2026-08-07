package com.m3man.data.model;

import java.io.Serializable;

/**
 * 版本升级
 *
 * @author flymegoc
 * @date 2017/12/22
 */

public class UpdateVersion implements Serializable {

    private static final long serialVersionUID = 1L;

    private int versionCode;
    private String versionName;
    private String updateMessage;
    private String apkDownloadUrl;
    /** 安装包 sha256（十六进制），用于下载后完整性校验，缺失则不校验（S1） */
    private String sha256;

    public int getVersionCode() {
        return versionCode;
    }

    public void setVersionCode(int versionCode) {
        this.versionCode = versionCode;
    }

    public String getVersionName() {
        return versionName;
    }

    public void setVersionName(String versionName) {
        this.versionName = versionName;
    }

    public String getUpdateMessage() {
        return updateMessage;
    }

    public void setUpdateMessage(String updateMessage) {
        this.updateMessage = updateMessage;
    }

    public String getApkDownloadUrl() {
        return apkDownloadUrl;
    }

    public void setApkDownloadUrl(String apkDownloadUrl) {
        this.apkDownloadUrl = apkDownloadUrl;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    @Override
    public String toString() {
        return "UpdateVersion{" +
                "versionCode=" + versionCode +
                ", versionName='" + versionName + '\'' +
                ", updateMessage='" + updateMessage + '\'' +
                ", apkDownloadUrl='" + apkDownloadUrl + '\'' +
                ", sha256='" + sha256 + '\'' +
                '}';
    }
}
