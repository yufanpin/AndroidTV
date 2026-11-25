package com.gary.ysplive.core.testutil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.gary.ysplive.core.util.Json;
import com.gary.ysplive.core.util.JsonException;

/**
 * 测试工具：从 {@code tools/} 目录读取由 Python 参考实现生成的向量 JSON。
 *
 * <p>测试运行时工作目录是 {@code core/}（Gradle 默认），所以先找 {@code ../tools/}，
 * 再逐级回退，最后支持 {@code -Dysp.tools.dir=...} 覆盖。
 */
public final class Vectors {

    private static final Map<String, Map<String, Object>> CACHE = new LinkedHashMap<>();

    private Vectors() {
    }

    public static Map<String, Object> load(String fileName) {
        synchronized (CACHE) {
            Map<String, Object> cached = CACHE.get(fileName);
            if (cached != null) {
                return cached;
            }
        }
        Map<String, Object> parsed;
        try {
            parsed = Json.parseObject(read(fileName));
        } catch (JsonException e) {
            throw new AssertionError("cannot parse vectors file " + fileName + ": " + e.getMessage(), e);
        } catch (IOException e) {
            throw new AssertionError("cannot read vectors file " + fileName + ": " + e.getMessage(), e);
        }
        synchronized (CACHE) {
            CACHE.put(fileName, parsed);
        }
        return parsed;
    }

    private static String read(String fileName) throws IOException {
        String[] candidates = candidatePaths(fileName);
        StringBuilder tried = new StringBuilder();
        for (String p : candidates) {
            java.io.File f = new java.io.File(p);
            if (!f.isFile()) {
                tried.append("\n  - ").append(f.getAbsolutePath());
                continue;
            }
            InputStream in = new java.io.FileInputStream(f);
            try {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream((int) f.length());
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bos.write(buf, 0, n);
                }
                return new String(bos.toByteArray(), StandardCharsets.UTF_8);
            } finally {
                in.close();
            }
        }
        throw new IOException("vectors file not found: " + fileName + ", tried:" + tried);
    }

    private static String[] candidatePaths(String fileName) {
        List<String> out = new ArrayList<>();
        String override = System.getProperty("ysp.tools.dir");
        if (override != null && !override.isEmpty()) {
            out.add(new java.io.File(override, fileName).getPath());
        }
        // 常见运行目录: C:\1\ysp-android\core  ->  C:\1\ysp-android\tools
        String cwd = new java.io.File(".").getAbsolutePath();
        java.io.File dir = new java.io.File(cwd);
        for (int i = 0; i < 5 && dir != null; i++) {
            out.add(new java.io.File(new java.io.File(dir, "tools"), fileName).getPath());
            out.add(new java.io.File(dir, fileName).getPath());
            dir = dir.getParentFile();
        }
        return out.toArray(new String[0]);
    }

    /** 取 {@code cases} 数组。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> cases(String fileName) {
        return items(fileName, "cases");
    }

    /** 取任意顶层数组字段（如 {@code encryptBlockCases} / {@code deterministicCases}）。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> items(String fileName, String key) {
        Object v = load(fileName).get(key);
        if (v == null) {
            throw new AssertionError("vectors file " + fileName + " has no field '" + key
                    + "'; got keys " + load(fileName).keySet());
        }
        return (List<Map<String, Object>>) v;
    }

    /** 取 {@code floatCases} 数组。 */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> floatCases(String fileName) {
        Object v = load(fileName).get("floatCases");
        return (List<Map<String, Object>>) v;
    }
}
