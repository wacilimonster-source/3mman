package com.m3man.ui.google;

public interface IGoogleRecaptchaVerify {

    void testV9Mman();

    void verifyGoogleRecaptcha(String action, String r, String id, String recaptcha);

    String getBaseAddress();
}
