package com.m3man.data.network;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.model.BaseResult;

import org.junit.Assume;
import org.junit.Test;

import java.util.List;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import static org.junit.Assert.*;

/** Opt-in integration check against the source's public video endpoints. */
public class Source441447LiveTest {
    @Test
    public void loadsCategoriesListAndPlayUrl() throws Exception {
        Assume.assumeTrue("1".equals(System.getenv("M3MAN_441447_LIVE_TEST")));
        Source441447Client client = new Source441447Client(new OkHttpClient());
        assertFalse(client.loadCategories().isEmpty());
        BaseResult<List<V9MmanItem>> result = client.loadVideos("", "latest", 1);
        assertFalse(result.getData().isEmpty());
        V9MmanItem item = result.getData().get(0);
        assertNotNull(HttpUrl.parse(client.loadPlayUrl(item.getViewKey())));
        try (Response cover = new OkHttpClient().newCall(new Request.Builder()
                .url(item.getImgUrl()).header("Range", "bytes=0-1").build()).execute()) {
            assertTrue("Cover request returned " + cover.code(), cover.isSuccessful());
        }
    }
}
