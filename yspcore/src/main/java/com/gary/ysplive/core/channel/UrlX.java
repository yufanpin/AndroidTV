package com.gary.ysplive.core.channel;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 轻量 URL 工具，等价于 Python {@code urllib.parse} 在本项目里用到的那几个函数。
 * <p>不用 {@link java.net.URI}：央视频的分片地址里有 {@code [}、编码不规范的字符，
 * {@code URI} 会直接抛异常，而 Python 的 {@code urlsplit}/{@code urljoin} 是宽容的。
 */
public final class UrlX {

    private UrlX() { }

    /** scheme（小写，不含 ':'），无法识别返回空串。 */
    public static String schemeOf(String url) {
        if (url == null) {
            return "";
        }
        int i = url.indexOf("://");
        if (i <= 0) {
            return "";
        }
        return url.substring(0, i).toLowerCase(java.util.Locale.ROOT);
    }

    /** host（小写，去掉 userinfo 与端口），无法识别返回空串。 */
    public static String hostOf(String url) {
        String authority = authorityOf(url);
        if (authority.isEmpty()) {
            return "";
        }
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        // IPv6 字面量 [::1]:80
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close > 0) {
                return authority.substring(0, close + 1).toLowerCase(java.util.Locale.ROOT);
            }
        }
        int colon = authority.lastIndexOf(':');
        String host = colon >= 0 ? authority.substring(0, colon) : authority;
        return host.toLowerCase(java.util.Locale.ROOT);
    }

    /** 端口；未显式指定返回 -1（对应 Python {@code parts.port} 为 None 的语义）。 */
    public static int portOf(String url) {
        String authority = authorityOf(url);
        if (authority.isEmpty()) {
            return -1;
        }
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        if (authority.startsWith("[")) {
            int close = authority.indexOf(']');
            if (close > 0 && close + 1 < authority.length() && authority.charAt(close + 1) == ':') {
                return parseIntOr(authority.substring(close + 2), -1);
            }
            return -1;
        }
        int colon = authority.lastIndexOf(':');
        if (colon < 0) {
            return -1;
        }
        return parseIntOr(authority.substring(colon + 1), -1);
    }

    /** path（以 '/' 开头；没有路径时返回空串，与 Python {@code urlsplit().path} 一致）。 */
    public static String pathOf(String url) {
        if (url == null) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        int start = schemeEnd < 0 ? 0 : schemeEnd + 3;
        int pathStart = -1;
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                pathStart = i;
                break;
            }
        }
        if (pathStart < 0) {
            return "";
        }
        int end = url.length();
        for (int i = pathStart; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return url.substring(pathStart, end);
    }

    /** query（不含 '?'）。 */
    public static String queryOf(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return "";
        }
        int hash = url.indexOf('#', q);
        return hash < 0 ? url.substring(q + 1) : url.substring(q + 1, hash);
    }

    /** authority（host[:port]，含可能的 userinfo）。 */
    public static String authorityOf(String url) {
        if (url == null) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return "";
        }
        int start = schemeEnd + 3;
        int end = url.length();
        for (int i = start; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        return url.substring(start, end);
    }

    /**
     * 相对地址拼绝对地址，等价于 Python {@code urllib.parse.urljoin} 的常用分支。
     * <p>支持：绝对 URL（直接返回）、{@code //host/path}、{@code /abs/path}、相对路径、
     * {@code ../} 上跳。
     */
    public static String urlJoin(String base, String ref) {
        if (ref == null || ref.isEmpty()) {
            return base == null ? "" : base;
        }
        if (base == null || base.isEmpty()) {
            return ref;
        }
        String refScheme = schemeOf(ref);
        if (!refScheme.isEmpty()) {
            return ref;
        }
        if (ref.startsWith("//")) {
            String baseScheme = schemeOf(base);
            return (baseScheme.isEmpty() ? "http" : baseScheme) + ":" + ref;
        }
        String baseScheme = schemeOf(base);
        String authority = authorityOf(base);
        if (baseScheme.isEmpty() || authority.isEmpty()) {
            return ref;
        }
        String origin = baseScheme + "://" + authority;
        if (ref.startsWith("/")) {
            return origin + removeDotSegments(ref);
        }
        String basePath = pathOf(base);
        if (basePath.isEmpty()) {
            basePath = "/";
        }
        int slash = basePath.lastIndexOf('/');
        String dir = slash >= 0 ? basePath.substring(0, slash + 1) : "/";
        return origin + removeDotSegments(dir + ref);
    }

    /** 处理 {@code .} 与 {@code ..} 段（保留前缀斜杠）。 */
    static String removeDotSegments(String path) {
        String[] parts = path.split("/", -1);
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        boolean trailingSlash = path.endsWith("/");
        for (int i = 0; i < parts.length; i++) {
            String seg = parts[i];
            if (i == 0) {
                // 第一段是空串（绝对路径前缀），保留
                stack.addLast(seg);
                continue;
            }
            if (seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (stack.size() > 1) {
                    stack.pollLast();
                }
                continue;
            }
            if (seg.isEmpty() && i == parts.length - 1) {
                continue;
            }
            stack.addLast(seg);
        }
        StringBuilder sb = new StringBuilder(path.length());
        boolean first = true;
        for (String seg : stack) {
            if (!first) {
                sb.append('/');
            }
            sb.append(seg);
            first = false;
        }
        String out = sb.toString();
        if (out.isEmpty()) {
            out = "/";
        }
        if (trailingSlash && !out.endsWith("/")) {
            out += "/";
        }
        return out;
    }

    /** 等价于 Python {@code urllib.parse.quote(s, safe='')}：除 {@code A-Za-z0-9_.-~} 外全部 %XX。 */
    public static String quote(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 32);
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-' || c == '~') {
                sb.append((char) c);
            } else {
                sb.append('%');
                sb.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                sb.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return sb.toString();
    }

    /** {@code application/x-www-form-urlencoded} 的单值编码。 */
    public static String formEncodeValue(String s) {
        if (s == null) {
            return "";
        }
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    /** 单值解码。 */
    public static String formDecode(String s) {
        if (s == null) {
            return "";
        }
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s;
        }
    }

    private static int parseIntOr(String s, int def) {
        if (s == null || s.isEmpty()) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
