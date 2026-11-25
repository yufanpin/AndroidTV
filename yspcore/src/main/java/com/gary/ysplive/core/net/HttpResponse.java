package com.gary.ysplive.core.net;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次 HTTP 响应的快照。对齐 Python 参考实现的 {@code HttpResponse}（line 742-748）。
 *
 * <p><b>关键约定</b>：非 2xx 也照样返回本对象（body 是错误页内容），只有传输层失败才抛
 * {@link java.io.IOException}。这与 Python {@code HttpClient._send} 的行为一致 —— 调用方
 * 需要拿 status 自己判断。
 */
public final class HttpResponse {

    /** HTTP 状态码。 */
    public final int status;
    /** 状态原因短语，可能为空串（不是 null）。 */
    public final String reason;
    /** 响应头：**key 一律小写**，同名头用 {@code ", "} 连接。不可变。 */
    public final Map<String, String> headers;
    /** 已解压（若原来是 gzip）的响应体。 */
    public final byte[] body;

    public HttpResponse(int status, String reason, Map<String, String> headers, byte[] body) {
        this.status = status;
        this.reason = reason == null ? "" : reason;
        this.headers = headers == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.body = body == null ? new byte[0] : body;
    }

    /** 按 UTF-8 解码；坏字节替换成 U+FFFD（等价 Python {@code errors='replace'}）。 */
    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** 取头（大小写不敏感，内部按小写索引）；不存在返回 null。 */
    public String header(String name) {
        if (name == null) {
            return null;
        }
        return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    /** 取头，缺失返回 {@code def}。 */
    public String header(String name, String def) {
        String v = header(name);
        return v == null ? def : v;
    }

    public String contentType() {
        return header("content-type", "");
    }

    /** Content-Length 头解析；缺失或非法返回 -1。注意 gzip 解压后它与 body.length 不一致。 */
    public long contentLength() {
        String v = header("content-length");
        if (v == null) {
            return -1L;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    /** 2xx 视为成功。 */
    public boolean isOk() {
        return status >= 200 && status < 300;
    }

    @Override
    public String toString() {
        return "HttpResponse{" + status + " " + reason + ", body=" + body.length + "B}";
    }
}
