package com.m3man.data.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.model.BaseResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Singleton;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.ByteString;

/** Client for the public video section and its normal browser session protocol. */
@Singleton
public class Source441447Client {
    public static final String SOURCE = "441447";
    public static final String KEY_PREFIX = "441447/";
    private static final String BASE = "https://www.442440.xyz/";
    /** M165：运行期迁移后的当前入口，供静态兜底链接 contentPageUrl 使用。 */
    private static volatile String currentBase = BASE;
    private static final String MODE = "cbc-hmac-v1";
    private static final String ALGORITHM = "AES-CBC-HMAC-SHA256";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36";
    private static final int PAGE_SIZE = 20;
    private final OkHttpClient http;
    /** 入口域名发生迁移时原地更新，后续请求直接走新域名。 */
    private volatile HttpUrl origin;
    private final SecureRandom random = new SecureRandom();
    private Session session;

    @Inject
    public Source441447Client(OkHttpClient sharedClient) {
        this(sharedClient, HttpUrl.parse(BASE));
    }

    Source441447Client(OkHttpClient sharedClient, HttpUrl origin) {
        // The application's debug HTTP logger must not record session secrets or signed URLs.
        OkHttpClient.Builder builder = sharedClient.newBuilder().cache(null)
                // 站点换域名时由 request() 自行把请求重挂到新域名，避免跟随重定向落到 HTML 欢迎页。
                .followRedirects(false).followSslRedirects(false);
        builder.interceptors().clear();
        builder.networkInterceptors().clear();
        http = builder.build();
        this.origin = origin;
        if (origin != null) {
            currentBase = origin.toString();
        }
    }

    public static boolean isSource(V9MmanItem item) {
        return item != null && (SOURCE.equals(item.getSourceName()) || SOURCE.equals(item.getSource())
                || (item.getViewKey() != null && item.getViewKey().startsWith(KEY_PREFIX)));
    }

    /** 源站网页详情地址，供原生播放失败时兜底打开（跟随运行期迁移后的入口）。 */
    public static String contentPageUrl(String viewKey) {
        String id = viewKey != null && viewKey.startsWith(KEY_PREFIX)
                ? viewKey.substring(KEY_PREFIX.length()) : "";
        return currentBase + "content/" + id;
    }

    public static String errorMessage(Throwable error) {
        return error instanceof HttpStatusException ? error.getMessage() : "来源加载失败，请重试";
    }

    public static final class Category {
        public final String id;
        public final String name;

        public Category(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    public List<Category> loadCategories() throws Exception {
        HttpUrl url = baseUrl().newBuilder().addPathSegments("api/front/categories")
                .addQueryParameter("contentType", "video").build();
        JsonElement data = signedGet(url);
        if (!data.isJsonArray()) throw new IOException("分类数据格式异常");
        List<Category> categories = new ArrayList<>();
        for (JsonElement element : data.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject category = element.getAsJsonObject();
            String id = string(category, "id");
            String name = string(category, "name");
            if (!id.isEmpty() && !name.isEmpty()) categories.add(new Category(id, name));
        }
        return categories;
    }

    public BaseResult<List<V9MmanItem>> loadVideos(String categoryId, String sort, int page) throws Exception {
        HttpUrl.Builder builder = baseUrl().newBuilder().addPathSegments("api/front/contents")
                .addQueryParameter("contentType", "video")
                .addQueryParameter("page", String.valueOf(Math.max(1, page)))
                .addQueryParameter("pageSize", String.valueOf(PAGE_SIZE))
                .addQueryParameter("sort", "hot".equals(sort) ? "hot" : "latest");
        if (categoryId != null && !categoryId.isEmpty()) builder.addQueryParameter("categoryId", categoryId);
        JsonElement data = signedGet(builder.build());
        if (!data.isJsonObject()) throw new IOException("视频列表格式异常");
        JsonObject object = data.getAsJsonObject();
        JsonElement itemsElement = object.get("items");
        if (itemsElement == null || !itemsElement.isJsonArray()) throw new IOException("视频列表格式异常");
        JsonArray items = itemsElement.getAsJsonArray();
        List<V9MmanItem> videos = new ArrayList<>();
        for (JsonElement element : items) {
            if (!element.isJsonObject()) continue;
            JsonObject video = element.getAsJsonObject();
            String id = string(video, "id");
            if (!id.matches("[0-9]+")) continue;
            V9MmanItem item = new V9MmanItem();
            item.setViewKey(KEY_PREFIX + id);
            item.setSourceName(SOURCE);
            item.setSource(SOURCE);
            item.setTitle(string(video, "title"));
            String cover = string(video, "coverUrl");
            HttpUrl coverUrl = cover.isEmpty() ? null : baseUrl().resolve(cover);
            item.setImgUrl(coverUrl == null ? "" : coverUrl.toString());
            long seconds = number(video, "duration");
            if (seconds > 0) {
                item.setDuration(seconds >= 3600
                        ? String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60)
                        : String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60));
            }
            videos.add(item);
        }
        long total = number(object, "total");
        BaseResult<List<V9MmanItem>> result = new BaseResult<>();
        result.setData(videos);
        result.setTotalPage((int) Math.max(1, Math.min(Integer.MAX_VALUE, (total + PAGE_SIZE - 1) / PAGE_SIZE)));
        return result;
    }

    public String loadPlayUrl(String viewKey) throws IOException {
        String id = viewKey != null && viewKey.startsWith(KEY_PREFIX) ? viewKey.substring(KEY_PREFIX.length()) : "";
        if (!id.matches("[0-9]+")) throw new IOException("视频编号无效");
        HttpUrl url = baseUrl().newBuilder().addPathSegments("api/front/videos").addPathSegment(id)
                .addPathSegment("play-url").build();
        RequestBody body = RequestBody.create(MediaType.parse("application/json; charset=utf-8"),
                "{\"resolution\":\"sd\",\"line\":\"normal\"}");
        JsonElement data = request(new Request.Builder().url(url).headers(headers()).post(body).build());
        if (!data.isJsonObject()) throw new IOException("播放数据格式异常");
        String raw = string(data.getAsJsonObject(), "playUrl");
        HttpUrl playUrl = baseUrl().resolve(raw);
        if (raw.isEmpty() || playUrl == null) throw new IOException("该视频暂无可用播放地址");
        return playUrl.toString();
    }

    private JsonElement signedGet(HttpUrl url) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            Session current = getSession();
            // 入口域名可能在 getSession() 中被换域，按最新 origin 重挂，避免多做一次重定向。
            HttpUrl target = url.newBuilder().scheme(origin.scheme())
                    .host(origin.host()).port(origin.port()).build();
            String timestamp = String.valueOf((System.currentTimeMillis() + current.clockOffset) / 1000);
            byte[] nonceBytes = new byte[18];
            random.nextBytes(nonceBytes);
            String nonce = base64Url(nonceBytes);
            String path = target.encodedPath() + (target.encodedQuery() == null ? "" : "?" + target.encodedQuery());
            String signature = base64Url(hmac(current.secret.getBytes(StandardCharsets.UTF_8),
                    "GET\n" + path + "\n" + timestamp + "\n" + nonce));
            HttpUrl signed = target.newBuilder().addQueryParameter("__fg_session", current.id)
                    .addQueryParameter("__fg_ts", timestamp).addQueryParameter("__fg_nonce", nonce)
                    .addQueryParameter("__fg_signature", signature).addQueryParameter("__fg_encrypt", MODE).build();
            Request request = new Request.Builder().url(signed).headers(headers())
                    .header("X-Front-Session", current.id).header("X-Front-Ts", timestamp)
                    .header("X-Front-Nonce", nonce).header("X-Front-Signature", signature)
                    .header("X-Front-Encrypt", MODE).build();
            try {
                return decrypt(request(request), current);
            } catch (HttpStatusException e) {
                if (e.status != 403 || attempt != 0) throw e;
                synchronized (this) {
                    if (session == current) session = null;
                }
            }
        }
        throw new IOException("来源暂时无法访问，请稍后重试");
    }

    private synchronized Session getSession() throws IOException {
        long now = System.currentTimeMillis();
        if (session != null && now + session.clockOffset < session.expiresAt - 60_000) return session;
        long started = now;
        HttpUrl url = baseUrl().newBuilder().addPathSegments("api/front/client-session").build();
        JsonElement data = request(new Request.Builder().url(url).headers(headers()).build());
        long finished = System.currentTimeMillis();
        if (!data.isJsonObject()) throw new IOException("来源会话不可用");
        JsonObject object = data.getAsJsonObject();
        String id = string(object, "sessionId");
        String secret = string(object, "secret");
        long serverTime = number(object, "serverEpochMillis");
        long expiry;
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date date = format.parse(string(object, "expiresAt"));
            expiry = date == null ? 0 : date.getTime();
        } catch (ParseException e) {
            throw new IOException("来源会话有效期无效", e);
        }
        if (id.isEmpty() || secret.isEmpty() || serverTime <= 0 || expiry <= serverTime) {
            throw new IOException("来源会话不可用");
        }
        session = new Session(id, secret, serverTime - (started + (finished - started) / 2), expiry);
        return session;
    }

    private JsonElement request(Request request) throws IOException {
        Request current = request;
        for (int redirects = 0; ; redirects++) {
            try (Response response = http.newCall(current).execute()) {
                if (isRedirect(response.code())) {
                    String location = response.header("Location");
                    HttpUrl target = location == null ? null : current.url().resolve(location);
                    if (redirects >= 3 || target == null || isSameOrigin(target, current.url())) {
                        throw new HttpStatusException(response.code());
                    }
                    // 站点换域名：保留原接口路径，只把请求改挂到新域名。
                    origin = new HttpUrl.Builder().scheme(target.scheme())
                            .host(target.host()).port(target.port()).build();
                    currentBase = origin.toString();
                    current = current.newBuilder().url(current.url().newBuilder()
                            .scheme(target.scheme()).host(target.host()).port(target.port()).build()).build();
                    continue;
                }
                if (!response.isSuccessful()) throw new HttpStatusException(response.code());
                if (response.body() == null) throw new IOException("来源返回空数据");
                JsonElement parsed = new JsonParser().parse(response.body().string());
                if (!parsed.isJsonObject()) throw new IOException("来源返回数据格式异常");
                JsonObject envelope = parsed.getAsJsonObject();
                if (!envelope.has("code") || number(envelope, "code") != 0
                        || !envelope.has("data") || envelope.get("data").isJsonNull()) {
                    throw new IOException("来源暂时无法提供数据，请稍后重试");
                }
                return envelope.get("data");
            } catch (RuntimeException e) {
                throw new IOException("来源返回数据格式异常", e);
            }
        }
    }

    private static boolean isRedirect(int code) {
        return code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
    }

    private static boolean isSameOrigin(HttpUrl a, HttpUrl b) {
        return a.scheme().equals(b.scheme()) && a.host().equals(b.host()) && a.port() == b.port();
    }

    private JsonElement decrypt(JsonElement data, Session current) throws Exception {
        if (!data.isJsonObject()) throw new IOException("来源响应未加密");
        JsonObject object = data.getAsJsonObject();
        if (!object.has("__encrypted") || !object.get("__encrypted").getAsBoolean()
                || !ALGORITHM.equals(string(object, "alg"))) throw new IOException("来源响应格式已变更");
        String iv = string(object, "iv");
        String payload = string(object, "payload");
        byte[] authKey = sha256("awesome-front-response-authentication-v1\n" + current.secret);
        byte[] expected = hmac(authKey, ALGORITHM + "\n" + iv + "\n" + payload);
        ByteString actual = ByteString.decodeBase64(string(object, "mac"));
        ByteString ivBytes = ByteString.decodeBase64(iv);
        ByteString ciphertext = ByteString.decodeBase64(payload);
        if (actual == null || ivBytes == null || ciphertext == null
                || !MessageDigest.isEqual(expected, actual.toByteArray())) throw new IOException("来源响应校验失败");
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sha256("awesome-front-response-encryption-v1\n" + current.secret), "AES"),
                new IvParameterSpec(ivBytes.toByteArray()));
        return new JsonParser().parse(new String(cipher.doFinal(ciphertext.toByteArray()), StandardCharsets.UTF_8));
    }

    private static byte[] sha256(String text) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] value) {
        return ByteString.of(value).base64Url().replace("=", "");
    }

    private HttpUrl baseUrl() { return origin; }

    private okhttp3.Headers headers() {
        return new okhttp3.Headers.Builder().add("User-Agent", USER_AGENT)
                .add("Referer", origin.resolve("list/video").toString()).add("Accept", "application/json").build();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    private static long number(JsonObject object, String key) {
        try { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : 0; }
        catch (RuntimeException e) { return 0; }
    }

    private static final class Session {
        final String id;
        final String secret;
        final long clockOffset;
        final long expiresAt;
        Session(String id, String secret, long clockOffset, long expiresAt) {
            this.id = id;
            this.secret = secret;
            this.clockOffset = clockOffset;
            this.expiresAt = expiresAt;
        }
    }

    private static final class HttpStatusException extends IOException {
        final int status;
        HttpStatusException(int status) {
            super(status == 429 ? "来源访问过于频繁，请稍后重试" : "来源暂时无法访问（" + status + "）");
            this.status = status;
        }
    }
}
