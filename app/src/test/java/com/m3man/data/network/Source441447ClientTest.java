package com.m3man.data.network;

import com.m3man.data.db.entity.V9MmanItem;
import com.m3man.data.model.BaseResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

import static org.junit.Assert.*;

public class Source441447ClientTest {
    private static final String SECRET = "test-session-secret";
    private static final String ALGORITHM = "AES-CBC-HMAC-SHA256";
    private ServerSocket server;
    private ServerSocket redirectServer;
    private Source441447Client client;
    private volatile int sessions;
    private volatile int redirects;
    private volatile boolean rejectNextRequest;
    private volatile boolean corruptMac;
    private volatile String lastListQuery;

    @Before
    public void startServer() throws IOException {
        java.net.InetAddress loopback = java.net.InetAddress.getByName("127.0.0.1");
        server = new ServerSocket(0, 16, loopback);
        final int apiPort = server.getLocalPort();
        Thread thread = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    handle(socket);
                } catch (IOException ignored) {
                    if (server.isClosed()) return;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        redirectServer = new ServerSocket(0, 16, loopback);
        Thread redirectThread = new Thread(() -> {
            while (!redirectServer.isClosed()) {
                try (Socket socket = redirectServer.accept()) {
                    redirects++;
                    drainRequest(socket);
                    String response = "HTTP/1.1 307 Test\r\nLocation: http://127.0.0.1:" + apiPort
                            + "/welcome\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    if (redirectServer.isClosed()) return;
                }
            }
        });
        redirectThread.setDaemon(true);
        redirectThread.start();
        HttpUrl origin = HttpUrl.parse("http://127.0.0.1:" + redirectServer.getLocalPort() + "/");
        client = new Source441447Client(new OkHttpClient(), origin);
    }

    @After
    public void stopServer() throws IOException {
        server.close();
        redirectServer.close();
    }

    @Test
    public void followsDomainRedirectForApiRequests() throws Exception {
        assertEquals(1, client.loadCategories().size());
        assertEquals(1, redirects);
        client.loadVideos("", "latest", 1);
        // 首次换域后 origin 已更新，后续请求不再经过入口域名。
        assertEquals(1, redirects);
        assertEquals(1, sessions);
    }

    @Test
    public void loadsSignedCategoriesAndPagesAndResolvesPlayback() throws Exception {
        List<Source441447Client.Category> categories = client.loadCategories();
        assertEquals(1, categories.size());
        assertEquals("5", categories.get(0).id);
        BaseResult<List<V9MmanItem>> result = client.loadVideos("5", "hot", 2);
        assertTrue(lastListQuery.contains("sort=hot"));
        assertTrue(lastListQuery.contains("categoryId=5"));
        assertTrue(lastListQuery.contains("page=2"));
        assertEquals(Integer.valueOf(3), result.getTotalPage());
        V9MmanItem item = result.getData().get(0);
        assertEquals("441447/123", item.getViewKey());
        assertEquals("441447", item.getSourceName());
        assertEquals("1:30", item.getDuration());
        assertTrue(item.getImgUrl().endsWith("/cover.webp"));
        assertEquals("https://cdn.example/video.m3u8", client.loadPlayUrl(item.getViewKey()));
        assertEquals(1, sessions);
    }

    @Test
    public void renewsSessionAfterAForbiddenSignedRequest() throws Exception {
        rejectNextRequest = true;
        assertEquals(1, client.loadCategories().size());
        assertEquals(2, sessions);
    }

    @Test
    public void rejectsResponsesWithInvalidMac() throws Exception {
        corruptMac = true;
        try {
            client.loadCategories();
            fail("Expected response authentication to fail");
        } catch (IOException expected) {
            assertEquals("来源响应校验失败", expected.getMessage());
        }
    }

    private void handle(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String[] request = reader.readLine().split(" ");
        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0) headers.put(line.substring(0, separator).toLowerCase(Locale.US), line.substring(separator + 1).trim());
        }
        int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
        char[] chars = new char[length];
        for (int offset = 0; offset < length;) {
            int read = reader.read(chars, offset, length - offset);
            if (read < 0) throw new IOException("Incomplete request");
            offset += read;
        }
        OutputStream output = socket.getOutputStream();
        try {
            String target = request[1];
            int queryStart = target.indexOf('?');
            String path = queryStart < 0 ? target : target.substring(0, queryStart);
            if (path.equals("/api/front/client-session")) {
                sessions++;
                SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                format.setTimeZone(TimeZone.getTimeZone("UTC"));
                String expiry = format.format(new Date(System.currentTimeMillis() + 600_000));
                respond(output, 200, "{\"code\":0,\"data\":{\"sessionId\":\"session\",\"secret\":\""
                        + SECRET + "\",\"serverEpochMillis\":" + System.currentTimeMillis()
                        + ",\"expiresAt\":\"" + expiry + "\"}}");
                return;
            }
            if (path.endsWith("/play-url")) {
                String body = new String(chars);
                if (!"POST".equals(request[0]) || !body.contains("\"resolution\":\"sd\"")) {
                    respond(output, 400, "{}");
                    return;
                }
                respond(output, 200, "{\"code\":0,\"data\":{\"playUrl\":\"https://cdn.example/video.m3u8\"}}");
                return;
            }
            if (rejectNextRequest) {
                rejectNextRequest = false;
                respond(output, 403, "{}");
                return;
            }
            String query = target.substring(queryStart + 1);
            int signatureParams = query.indexOf("&__fg_session=");
            String originalPath = path + "?" + query.substring(0, signatureParams);
            String timestamp = headers.get("x-front-ts");
            String nonce = headers.get("x-front-nonce");
            String actualSignature = headers.get("x-front-signature");
            String signature = base64Url(hmac(SECRET.getBytes(StandardCharsets.UTF_8),
                    "GET\n" + originalPath + "\n" + timestamp + "\n" + nonce));
            if (!signature.equals(actualSignature)) {
                respond(output, 403, "{}");
                return;
            }
            String data;
            if (path.endsWith("/categories")) {
                data = "[{\"id\":5,\"name\":\"分类\"}]";
            } else {
                lastListQuery = query;
                data = "{\"total\":41,\"items\":[{\"id\":123,\"title\":\"示例\","
                        + "\"coverUrl\":\"/cover.webp\",\"duration\":90}]}";
            }
            respond(output, 200, encrypted(data));
        } catch (Exception error) {
            respond(output, 500, "{}");
        }
    }

    private String encrypted(String data) throws Exception {
        byte[] iv = new byte[16];
        byte[] key = sha256("awesome-front-response-encryption-v1\n" + SECRET);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        String ivText = Base64.getEncoder().encodeToString(iv);
        String payload = Base64.getEncoder().encodeToString(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        byte[] auth = sha256("awesome-front-response-authentication-v1\n" + SECRET);
        String mac = base64Url(hmac(auth, ALGORITHM + "\n" + ivText + "\n" + payload));
        if (corruptMac) mac = "invalid";
        return "{\"code\":0,\"data\":{\"__encrypted\":true,\"alg\":\"" + ALGORITHM
                + "\",\"iv\":\"" + ivText + "\",\"payload\":\"" + payload + "\",\"mac\":\"" + mac + "\"}}";
    }

    private static void drainRequest(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        if (reader.readLine() == null) return;
        int length = 0;
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0 && "content-length".equalsIgnoreCase(line.substring(0, separator).trim())) {
                try {
                    length = Integer.parseInt(line.substring(separator + 1).trim());
                } catch (NumberFormatException ignored) {
                    length = 0;
                }
            }
        }
        for (int offset = 0; offset < length; ) {
            int read = reader.read(new char[Math.min(1024, length - offset)]);
            if (read < 0) return;
            offset += read;
        }
    }

    private static byte[] sha256(String text) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private static void respond(OutputStream output, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String headers = "HTTP/1.1 " + status + " Test\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n";
        output.write(headers.getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        output.flush();
    }
}
