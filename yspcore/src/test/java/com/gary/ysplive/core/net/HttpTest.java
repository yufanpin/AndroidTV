package com.gary.ysplive.core.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.gary.ysplive.core.testutil.TinyHttpServer;
import com.gary.ysplive.core.testutil.Vectors;
import com.gary.ysplive.core.util.Json;

/**
 * {@link Http} 集成测试：用 {@link TinyHttpServer}（原始 {@link java.net.ServerSocket} 实现）
 * 起一个真实 HTTP 服务，验证 GET/POST/gzip/重定向/错误码/Range/表单编码/header 透传/超时。
 *
 * <p>不用 {@code com.sun.net.httpserver}：Gradle 的 Android 单元测试 bootclasspath 是 android.jar，
 * {@code jdk.httpserver} 模块不可见（编译期"找不到符号"），而 {@code core/build.gradle} 由 Lead 独占。
 */
public class HttpTest {

    private TinyHttpServer server;
    private String base;

    @Before
    public void setUp() throws IOException {
        server = new TinyHttpServer();

        server.route("/echo", req -> {
            String json = "{\"method\":\"" + req.method + "\",\"path\":\"" + req.path + "\"}";
            return TinyHttpServer.Resp.of(200, "OK", json)
                    .header("Content-Type", "application/json")
                    .header("X-Test-Header", "hello");
        });

        server.route("/gzip", req -> {
            byte[] plain = "这是一个被 gzip 压缩的中文响应体 / gzip test"
                    .getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            GZIPOutputStream gz = new GZIPOutputStream(bos);
            gz.write(plain);
            gz.finish();
            gz.close();
            TinyHttpServer.Resp r = new TinyHttpServer.Resp();
            r.status = 200;
            r.reason = "OK";
            r.body = bos.toByteArray();
            r.bodyIsEncoded = true;
            r.header("Content-Type", "text/plain; charset=utf-8");
            r.header("Content-Encoding", "gzip");
            return r;
        });

        server.route("/fakegzip", req -> {
            TinyHttpServer.Resp r = TinyHttpServer.Resp.of(200, "OK", "not actually gzip");
            r.header("Content-Encoding", "gzip");
            return r;
        });

        server.route("/redirect", req -> {
            TinyHttpServer.Resp r = new TinyHttpServer.Resp();
            r.status = 302;
            r.reason = "Found";
            r.body = new byte[0];
            r.header("Location", base + "/echo");
            return r;
        });

        server.route("/notfound", req -> TinyHttpServer.Resp.of(404, "Not Found", "resource not found"));

        server.route("/error500", req ->
                TinyHttpServer.Resp.of(500, "Internal Server Error", "{\"err\":\"internal\"}"));

        server.route("/big", req -> {
            int total = 300 * 1024;
            byte[] all = new byte[total];
            for (int i = 0; i < total; i++) {
                all[i] = (byte) (i & 0xFF);
            }
            TinyHttpServer.Resp r = new TinyHttpServer.Resp();
            r.status = 200;
            r.reason = "OK";
            r.body = all;
            r.header("Content-Type", "video/mp2t");
            return r;
        });

        server.route("/range", req -> {
            byte[] all = new byte[1000];
            for (int i = 0; i < all.length; i++) {
                all[i] = (byte) (i & 0xFF);
            }
            String range = req.header("range");
            TinyHttpServer.Resp r = new TinyHttpServer.Resp();
            r.header("Content-Type", "video/mp2t");
            if (range != null && range.startsWith("bytes=")) {
                String spec = range.substring("bytes=".length());
                int dash = spec.indexOf('-');
                int start = Integer.parseInt(spec.substring(0, dash).trim());
                int end = spec.length() > dash + 1
                        ? Integer.parseInt(spec.substring(dash + 1).trim()) : all.length - 1;
                byte[] part = java.util.Arrays.copyOfRange(all, start, Math.min(end + 1, all.length));
                r.status = 206;
                r.reason = "Partial Content";
                r.body = part;
                r.header("Content-Range", "bytes " + start + "-" + end + "/" + all.length);
            } else {
                r.status = 200;
                r.reason = "OK";
                r.body = all;
            }
            return r;
        });

        server.route("/headers", req -> TinyHttpServer.Resp.of(200, "OK", "ok"));

        server.route("/slow", req -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TinyHttpServer.Resp.of(200, "OK", "late");
        });

        base = server.base();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private Map<String, String> sentHeaders() {
        return server.last().headers;
    }

    // ------------------------------------------------------------ 基础

    @Test
    public void getReturnsBodyAndHeaders() throws IOException {
        try (Http http = new Http(5000)) {
            HttpResponse r = http.get(base + "/echo", null);
            assertEquals(200, r.status);
            assertTrue("reason must not be null", r.reason != null);
            assertTrue(r.isOk());
            assertTrue(r.text().contains("\"method\":\"GET\""));
            assertEquals("hello", r.header("x-test-header"));
            assertEquals("hello", r.header("X-Test-Header"));
            assertNotNull(r.header("content-type"));
            for (String k : r.headers.keySet()) {
                assertEquals("header keys must be lowercase: " + k,
                        k.toLowerCase(java.util.Locale.ROOT), k);
            }
        }
    }

    @Test
    public void postBytesSendsBody() throws IOException {
        try (Http http = new Http(5000)) {
            byte[] body = "{\"hello\":\"世界\"}".getBytes(StandardCharsets.UTF_8);
            HttpResponse r = http.postBytes(base + "/echo", null, body);
            assertEquals(200, r.status);
            assertArrayEquals(body, server.last().body);
            assertEquals("POST", server.last().method);
            assertEquals(String.valueOf(body.length), sentHeaders().get("content-length"));
        }
    }

    @Test
    public void postJsonMatchesPythonSerialization() throws IOException {
        try (Http http = new Http(5000)) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("cctv_id", "X");
            value.put("idfa", "");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("key", "app_start_d1");
            body.put("value", value);
            HttpResponse r = http.postJson(base + "/echo", null, body);
            assertEquals(200, r.status);
            assertEquals("{\"key\":\"app_start_d1\",\"value\":{\"cctv_id\":\"X\",\"idfa\":\"\"}}",
                    server.last().bodyText());
        }
    }

    // ------------------------------------------------------------ gzip

    @Test
    public void gzipIsInflated() throws IOException {
        try (Http http = new Http(5000)) {
            HttpResponse r = http.get(base + "/gzip", null);
            assertEquals(200, r.status);
            assertEquals("这是一个被 gzip 压缩的中文响应体 / gzip test", r.text());
            assertEquals("gzip", r.header("content-encoding"));
        }
    }

    @Test
    public void defaultAcceptEncodingIsIdentity() throws IOException {
        try (Http http = new Http(5000)) {
            http.get(base + "/echo", null);
            assertEquals("must send Accept-Encoding: identity to keep Content-Length honest",
                    "identity", sentHeaders().get("accept-encoding"));
        }
    }

    @Test
    public void callerAcceptEncodingIsRespected() throws IOException {
        try (Http http = new Http(5000)) {
            Map<String, String> hdrs = new LinkedHashMap<>();
            hdrs.put("Accept-Encoding", "gzip");
            http.get(base + "/echo", hdrs);
            assertEquals("gzip", sentHeaders().get("accept-encoding"));
        }
    }

    @Test
    public void fakeGzipDoesNotExplode() throws IOException {
        try (Http http = new Http(5000)) {
            HttpResponse r = http.get(base + "/fakegzip", null);
            assertEquals(200, r.status);
            // 解压失败时按原文返回（对齐 Python 的 except: pass）
            assertEquals("not actually gzip", r.text());
        }
    }

    // ------------------------------------------------------------ 重定向

    @Test
    public void followsRedirectByDefault() throws IOException {
        try (Http http = new Http(5000)) {
            HttpResponse r = http.get(base + "/redirect", null);
            assertEquals(200, r.status);
            assertTrue(r.text().contains("\"path\":\"/echo\""));
        }
    }

    @Test
    public void doesNotFollowRedirectWhenDisabled() throws IOException {
        try (Http http = new Http(5000)) {
            HttpResponse r = http.request("GET", base + "/redirect", null, null, false);
            assertEquals("must surface the raw 3xx", 302, r.status);
            assertEquals(base + "/echo", r.header("location"));
        }
    }

    // ------------------------------------------------------------ 错误码

    @Test
    public void errorStatusReturnsResponseNotException() throws IOException {
        try (Http http = new Http(5000)) {
            HttpResponse r404 = http.get(base + "/notfound", null);
            assertEquals(404, r404.status);
            assertTrue(!r404.isOk());
            assertEquals("resource not found", r404.text());

            HttpResponse r500 = http.get(base + "/error500", null);
            assertEquals(500, r500.status);
            Map<String, Object> m = Json.tryParseObject(r500.text());
            assertNotNull(m);
            assertEquals("internal", Json.str(m, "err", ""));
        }
    }

    @Test
    public void transportFailureThrowsIOException() throws IOException {
        try (Http http = new Http(1000)) {
            try {
                http.get("http://127.0.0.1:1/nothing", null);
                fail("connection refused must throw IOException");
            } catch (IOException expected) {
                String msg = expected.getMessage();
                assertNotNull(msg);
                assertTrue("message should be normalized, got: " + msg,
                        msg.contains("connection refused") || msg.contains("network error"));
            }
        }
    }

    @Test
    public void readTimeoutIsNormalized() throws IOException {
        try (Http http = new Http(400)) {
            try {
                http.get(base + "/slow", null);
                fail("read timeout must throw IOException");
            } catch (IOException expected) {
                String msg = expected.getMessage();
                assertTrue("message should mention timeout, got: " + msg,
                        msg.contains("timed out") || msg.contains("network error"));
            }
        }
    }

    // ------------------------------------------------------------ 流式

    @Test
    public void streamDeliversChunks() throws IOException {
        try (Http http = new Http(5000)) {
            final AtomicReference<Integer> statusRef = new AtomicReference<>();
            final AtomicReference<Map<String, String>> headersRef = new AtomicReference<>();
            final ByteArrayOutputStream got = new ByteArrayOutputStream();
            http.stream(base + "/big", null, null, new Http.StreamSink() {
                @Override
                public void onHeaders(int status, Map<String, String> headers) {
                    statusRef.set(status);
                    headersRef.set(headers);
                }

                @Override
                public void onBytes(byte[] buf, int off, int len) {
                    got.write(buf, off, len);
                }
            });
            assertEquals(Integer.valueOf(200), statusRef.get());
            assertNotNull(headersRef.get());
            assertEquals(300 * 1024, got.size());
            byte[] all = got.toByteArray();
            for (int i = 0; i < all.length; i++) {
                assertEquals("byte at " + i, (byte) (i & 0xFF), all[i]);
            }
        }
    }

    @Test
    public void streamHonorsRange() throws IOException {
        try (Http http = new Http(5000)) {
            final ByteArrayOutputStream got = new ByteArrayOutputStream();
            final AtomicReference<Integer> statusRef = new AtomicReference<>();
            http.stream(base + "/range", null, new Http.Range(100, 199), new Http.StreamSink() {
                @Override
                public void onHeaders(int status, Map<String, String> headers) {
                    statusRef.set(status);
                }

                @Override
                public void onBytes(byte[] buf, int off, int len) {
                    got.write(buf, off, len);
                }
            });
            assertEquals(Integer.valueOf(206), statusRef.get());
            assertEquals(100, got.size());
            byte[] all = got.toByteArray();
            assertEquals((byte) 100, all[0]);
            assertEquals((byte) 199, all[99]);
            assertEquals("bytes=100-199", sentHeaders().get("range"));
        }
    }

    @Test
    public void rangeHeaderValueFormat() {
        assertEquals("bytes=0-", new Http.Range(0, -1).headerValue());
        assertEquals("bytes=0-99", new Http.Range(0, 99).headerValue());
        assertEquals("bytes=100-", new Http.Range(100, -1).headerValue());
        assertEquals("bytes=100-199", new Http.Range(100, 199).headerValue());
    }

    // ------------------------------------------------------------ header 透传

    @Test
    public void callerHeadersArePassedThrough() throws IOException {
        try (Http http = new Http(5000)) {
            Map<String, String> hdrs = new LinkedHashMap<>();
            hdrs.put("User-Agent", "qqlive");
            hdrs.put("Referer", "https://live.cctv.cn/");
            hdrs.put("UID", "abc123");
            hdrs.put("APPID", "app-id-value");
            hdrs.put("APPSIGN", "sign-value");
            hdrs.put("APPRANDOMSTR", "random-str");
            hdrs.put("Range", "bytes=0-10");
            http.get(base + "/echo", hdrs);
            Map<String, String> sent = sentHeaders();
            assertEquals("qqlive", sent.get("user-agent"));
            assertEquals("https://live.cctv.cn/", sent.get("referer"));
            assertEquals("abc123", sent.get("uid"));
            assertEquals("app-id-value", sent.get("appid"));
            assertEquals("sign-value", sent.get("appsign"));
            assertEquals("random-str", sent.get("apprandomstr"));
            assertEquals("bytes=0-10", sent.get("range"));
        }
    }

    /**
     * Host 头：JVM 视为 restricted header，会被静默丢弃，实际发出的是 URL 的 host。
     * 把这个平台行为钉成测试，避免以后误以为能改 Host。
     */
    @Test
    public void hostHeaderIsDroppedNotHonored() throws IOException {
        try (Http http = new Http(5000)) {
            Map<String, String> hdrs = new LinkedHashMap<>();
            hdrs.put("Host", "evil.example.com");
            http.get(base + "/echo", hdrs);
            String sentHost = sentHeaders().get("host");
            assertNotNull(sentHost);
            assertTrue("actual Host must be the URL host:port, got " + sentHost,
                    sentHost.startsWith("127.0.0.1:"));
            assertTrue("must not be the caller-supplied Host", !sentHost.contains("evil"));
        }
    }

    @Test
    public void headerInjectionIsCleaned() throws IOException {
        try (Http http = new Http(5000)) {
            Map<String, String> hdrs = new LinkedHashMap<>();
            hdrs.put("X-Clean", "value\r\nInjected: evil");
            http.get(base + "/echo", hdrs);
            assertNull("must not allow header injection", sentHeaders().get("injected"));
        }
    }

    // ------------------------------------------------------------ 表单编码

    @Test
    public void postFormEncodesLikePython() throws IOException {
        try (Http http = new Http(5000)) {
            List<String[]> pairs = new ArrayList<>();
            pairs.add(new String[]{"appcommon", "{\"a\":1,\"b\":\"中文 /x\"}"});
            HttpResponse r = http.postForm(base + "/echo", null, pairs);
            assertEquals(200, r.status);
            String sent = server.last().bodyText();
            assertTrue("form body should start with appcommon=", sent.startsWith("appcommon="));
            assertTrue("must percent-encode / as %2F: " + sent, sent.contains("%2F"));
            assertTrue("must percent-encode { as %7B: " + sent, sent.contains("%7B"));
            assertTrue("must use uppercase hex: " + sent, sent.contains("%E4"));
            assertTrue("must NOT contain lowercase %e4", !sent.contains("%e4"));
            assertEquals("application/x-www-form-urlencoded",
                    sentHeaders().get("content-type"));
        }
    }

    @Test
    public void formEncodeValueMatchesPythonVectors() {
        Map<String, Object> root = Vectors.load("vectors-form.json");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> valueCases = (List<Map<String, Object>>) root.get("valueCases");
        int checked = 0;
        for (Map<String, Object> c : valueCases) {
            if (c.containsKey("pairs")) {
                continue;
            }
            String value = Json.str(c, "value", "");
            String want = Json.str(c, "encoded", "");
            assertEquals("form encode mismatch for <" + value + ">", want,
                    Http.formEncodeValue(value));
            checked++;
        }
        assertTrue("should check form vectors", checked > 0);
    }

    @Test
    public void formEncodePairsMatchesPython() {
        Map<String, Object> root = Vectors.load("vectors-form.json");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> valueCases = (List<Map<String, Object>>) root.get("valueCases");
        for (Map<String, Object> c : valueCases) {
            if (!c.containsKey("pairs")) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<List<String>> pairs = (List<List<String>>) c.get("pairs");
            List<String[]> in = new ArrayList<>();
            for (List<String> p : pairs) {
                in.add(new String[]{p.get(0), p.get(1)});
            }
            String got = new String(Http.formEncode(in), StandardCharsets.UTF_8);
            assertEquals("form encode pairs mismatch", Json.str(c, "encoded", ""), got);
        }
    }

    // ------------------------------------------------------------ 其它

    @Test
    public void responseJsonParsesBody() throws IOException {
        try (Http http = new Http(5000)) {
            HttpResponse r = http.get(base + "/echo", null);
            Map<String, Object> m = Http.responseJson(r);
            assertNotNull(m);
            assertEquals("GET", Json.str(m, "method", ""));
        }
    }

    @Test
    public void invalidUrlThrows() {
        try (Http http = new Http(1000)) {
            http.get("not-a-url", null);
            fail("invalid URL must throw IOException");
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void unsupportedSchemeThrows() {
        try (Http http = new Http(1000)) {
            http.get("ftp://example.com/x", null);
            fail("unsupported scheme must throw IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("scheme"));
        }
    }

    @Test
    public void contentLengthAccessor() throws IOException {
        try (Http http = new Http(5000)) {
            HttpResponse r = http.get(base + "/echo", null);
            assertTrue(r.contentLength() > 0);
            assertEquals(r.body.length, r.contentLength());
        }
    }
}
