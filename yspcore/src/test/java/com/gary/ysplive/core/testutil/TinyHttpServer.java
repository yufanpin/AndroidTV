package com.gary.ysplive.core.testutil;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 极简 HTTP/1.1 测试服务器，只用于单元测试。
 *
 * <p><b>为什么不用 {@code com.sun.net.httpserver.HttpServer}：</b>Gradle 的 Android 单元测试
 * 用的是 android.jar 的 bootclasspath，{@code jdk.httpserver} 模块不可见（编译期直接"找不到符号"），
 * 而改 {@code core/build.gradle} 是 Lead 独占的。所以这里用最原始的
 * {@link ServerSocket} 手写一个够用的服务器 —— 反而更可控（能精确构造 gzip / 302 / 500 /
 * 假 gzip / Range 等响应）。
 *
 * <p>每个连接处理一个请求后关闭（{@code Connection: close}），足够覆盖本项目的用例。
 */
public final class TinyHttpServer implements AutoCloseable {

    /** 一次被捕获的请求。 */
    public static final class Req {
        public String method = "";
        public String path = "";
        public String query = "";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        public String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** 路由：返回要写回的响应。 */
    public interface Route {
        Resp handle(Req req) throws IOException;
    }

    /** 要写回的响应。 */
    public static final class Resp {
        public int status = 200;
        public String reason = "OK";
        public final Map<String, String> headers = new LinkedHashMap<>();
        public byte[] body = new byte[0];
        /** true 表示 body 已经是 gzip 压缩后的字节，配合 Content-Encoding: gzip 用。 */
        public boolean bodyIsEncoded;

        public static Resp of(int status, String reason, String text) {
            Resp r = new Resp();
            r.status = status;
            r.reason = reason;
            r.body = text.getBytes(StandardCharsets.UTF_8);
            return r;
        }

        public Resp header(String k, String v) {
            headers.put(k, v);
            return this;
        }
    }

    private final ServerSocket socket;
    private final Map<String, Route> routes = new LinkedHashMap<>();
    private final List<Req> captured = new ArrayList<>();
    private final Thread thread;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public TinyHttpServer() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        thread = new Thread(this::loop, "tiny-http-test-server");
        thread.setDaemon(true);
        thread.start();
    }

    public int port() {
        return socket.getLocalPort();
    }

    public String base() {
        return "http://127.0.0.1:" + port();
    }

    public TinyHttpServer route(String path, Route route) {
        routes.put(path, route);
        return this;
    }

    /** 最近一次捕获的请求。 */
    public Req last() {
        synchronized (captured) {
            return captured.isEmpty() ? null : captured.get(captured.size() - 1);
        }
    }

    public List<Req> all() {
        synchronized (captured) {
            return new ArrayList<>(captured);
        }
    }

    private void loop() {
        while (running.get()) {
            Socket client;
            try {
                client = socket.accept();
            } catch (IOException e) {
                return; // socket 已关闭
            }
            try {
                handle(client);
            } catch (IOException ignore) {
                // 单个连接失败不影响服务器
            } finally {
                try {
                    client.close();
                } catch (IOException ignore) {
                    // 忽略
                }
            }
        }
    }

    private void handle(Socket client) throws IOException {
        InputStream in = new BufferedInputStream(client.getInputStream());
        Req req = readRequest(in);
        if (req == null) {
            return;
        }
        synchronized (captured) {
            captured.add(req);
        }
        Route route = routes.get(req.path);
        Resp resp;
        if (route == null) {
            resp = Resp.of(404, "Not Found", "no route: " + req.path);
        } else {
            try {
                resp = route.handle(req);
            } catch (RuntimeException e) {
                resp = Resp.of(500, "Internal Server Error", "test route threw: " + e);
            }
        }
        if (resp == null) {
            resp = Resp.of(500, "Internal Server Error", "null response");
        }
        writeResponse(client.getOutputStream(), req, resp);
    }

    private Req readRequest(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int prev3 = -1;
        int prev2 = -1;
        int prev1 = -1;
        while (true) {
            int c = in.read();
            if (c < 0) {
                return head.length() == 0 ? null : throwBadRequest("truncated request head");
            }
            head.append((char) c);
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && c == '\n') {
                break;
            }
            prev3 = prev2;
            prev2 = prev1;
            prev1 = c;
            if (head.length() > 64 * 1024) {
                throwBadRequest("request head too large");
            }
        }
        String[] lines = head.toString().split("\r\n");
        if (lines.length == 0) {
            return throwBadRequest("empty request");
        }
        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) {
            return throwBadRequest("bad request line: " + lines[0]);
        }
        Req req = new Req();
        req.method = requestLine[0];
        String target = requestLine[1];
        int q = target.indexOf('?');
        if (q >= 0) {
            req.path = target.substring(0, q);
            req.query = target.substring(q + 1);
        } else {
            req.path = target;
        }
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String k = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String v = line.substring(colon + 1).trim();
            String existing = req.headers.get(k);
            req.headers.put(k, existing == null ? v : existing + "," + v);
        }
        String cl = req.header("content-length");
        if (cl != null) {
            int len;
            try {
                len = Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                return throwBadRequest("bad content-length: " + cl);
            }
            byte[] body = new byte[len];
            int read = 0;
            while (read < len) {
                int n = in.read(body, read, len - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            req.body = body;
        }
        return req;
    }

    private Req throwBadRequest(String msg) throws IOException {
        throw new IOException("bad request: " + msg);
    }

    private static void writeResponse(OutputStream out, Req req, Resp resp) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        String statusLine = "HTTP/1.1 " + resp.status + " " + resp.reason + "\r\n";
        head.write(statusLine.getBytes(StandardCharsets.US_ASCII));
        boolean hasLength = false;
        for (Map.Entry<String, String> e : resp.headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase("Content-Length")) {
                hasLength = true;
            }
            head.write((e.getKey() + ": " + e.getValue() + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        if (!hasLength) {
            head.write(("Content-Length: " + resp.body.length + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        }
        head.write("Connection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.write(head.toByteArray());
        if (!"HEAD".equalsIgnoreCase(req.method) && resp.body.length > 0) {
            out.write(resp.body);
        }
        out.flush();
    }

    @Override
    public void close() {
        running.set(false);
        try {
            socket.close();
        } catch (IOException ignore) {
            // 忽略
        }
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
