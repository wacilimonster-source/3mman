package com.m3man.exception;

import com.google.gson.JsonSyntaxException;

import org.junit.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertEquals;

/**
 * M101：ApiException 异常分类回归——重点覆盖 M95 修复的
 * 「instanceof JsonSerializer 误写导致 Gson 解析错误落入 UNKNOWN」问题。
 */
public class ApiExceptionTypeTest {

    @Test
    public void jsonSyntaxExceptionClassifiedAsParseError() {
        ApiException ex = ApiException.handleException(new JsonSyntaxException("bad json"));
        assertEquals(ApiException.Error.PARSE_ERROR, ex.getCode());
    }

    @Test
    public void connectExceptionClassifiedAsNetworkError() {
        ApiException ex = ApiException.handleException(new ConnectException("refused"));
        assertEquals(ApiException.Error.NETWORD_ERROR, ex.getCode());
    }

    @Test
    public void unknownStaysUnknown() {
        ApiException ex = ApiException.handleException(new IllegalStateException("weird"));
        assertEquals(ApiException.Error.UNKNOWN, ex.getCode());
    }
}
