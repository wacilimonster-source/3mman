package com.m3man.exception;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-11 实测回归：作者页 token 失效 / 下载 404 时，界面会直接弹出
 * okhttp 的英文原因短语 "HTTP 404 Not Found"；现在统一转成中文提示。
 */
public class ApiExceptionHttpMessageTest {

    private String map(int code) throws Exception {
        Method m = ApiException.class.getDeclaredMethod("httpErrorMessage", int.class);
        m.setAccessible(true);
        return (String) m.invoke(null, code);
    }

    @Test
    public void notFoundMessageIsChinese() throws Exception {
        String message = map(404);
        assertEquals("内容不存在或链接已失效（404）", message);
        assertFalse("不应把英文原因短语透给用户", message.contains("Not Found"));
    }

    @Test
    public void commonCodesAreMapped() throws Exception {
        assertEquals("登录状态已失效，请重新登录（401）", map(401));
        assertEquals("请求无效（400）", map(400));
        assertEquals("访问被拒绝（403）", map(403));
        assertEquals("请求超时（408）", map(408));
        assertEquals("服务器内部错误（500）", map(500));
        assertEquals("网关错误（502）", map(502));
        assertEquals("服务暂不可用（503）", map(503));
        assertEquals("网关超时（504）", map(504));
    }

    @Test
    public void unknownStatusCodeKeepsNumber() throws Exception {
        String message = map(418);
        assertTrue(message.contains("418"));
        assertFalse(message.contains("teapot"));
    }
}
