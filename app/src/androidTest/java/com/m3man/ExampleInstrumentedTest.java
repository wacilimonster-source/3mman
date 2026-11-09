package com.m3man;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class ExampleInstrumentedTest {
    @Test
    public void useAppContext() throws Exception {
        // Context of the app under test.
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        // M165：applicationId 是 com.m3man；benchmark 变体带 .perf 后缀，
        // 旧断言 com.u91mman 必失败。按前缀校验，兼容正式与测试变体。
        assertTrue(appContext.getPackageName().startsWith("com.m3man"));
    }
}
