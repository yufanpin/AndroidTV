package com.gary.ysplive.core.server;

import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 输出器，只服务于 {@code /health} 与 {@code /diag} 这类诊断输出。
 * <p>为什么不用 {@code core.util.Json}：{@code /health} 的字节形状要和 Python
 * {@code json.dumps(..., ensure_ascii=False, indent=2)} 完全一致（缩进两空格、
 * {@code ": "} 分隔、浮点 {@code 0.0} 形态、不转义中文），这条路由自带一个输出器
 * 最省心，也避免 core.server 与 core.util 的构建顺序耦合。
 *
 * <p>只支持本项目实际用到的类型：Map / List / String / Number / Boolean / null。
 */
final class MiniJson {

    private MiniJson() { }

    /** 等价 {@code json.dumps(value, ensure_ascii=False, indent=2)}。 */
    static String pretty(Object value) {
        StringBuilder sb = new StringBuilder(1024);
        write(sb, value, 0, true);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, int indent, boolean pretty) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String) {
            writeString(sb, (String) value);
            return;
        }
        if (value instanceof Boolean) {
            sb.append(((Boolean) value) ? "true" : "false");
            return;
        }
        if (value instanceof Double || value instanceof Float) {
            sb.append(formatDouble(((Number) value).doubleValue()));
            return;
        }
        if (value instanceof Number) {
            sb.append(value.toString());
            return;
        }
        if (value instanceof Map) {
            writeMap(sb, (Map<?, ?>) value, indent, pretty);
            return;
        }
        if (value instanceof List) {
            writeList(sb, (List<?>) value, indent, pretty);
            return;
        }
        writeString(sb, String.valueOf(value));
    }

    private static void writeMap(StringBuilder sb, Map<?, ?> map, int indent, boolean pretty) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (pretty) {
                sb.append('\n');
                indent(sb, indent + 1);
            }
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            if (pretty) {
                sb.append(' ');
            }
            write(sb, e.getValue(), indent + 1, pretty);
        }
        if (pretty) {
            sb.append('\n');
            indent(sb, indent);
        }
        sb.append('}');
    }

    private static void writeList(StringBuilder sb, List<?> list, int indent, boolean pretty) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append('[');
        boolean first = true;
        for (Object item : list) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            if (pretty) {
                sb.append('\n');
                indent(sb, indent + 1);
            }
            write(sb, item, indent + 1, pretty);
        }
        if (pretty) {
            sb.append('\n');
            indent(sb, indent);
        }
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }

    /** Python 浮点输出：整数值补 {@code .0}（{@code 0.0} / {@code 12.0}）。 */
    private static String formatDouble(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) {
            return (long) v + ".0";
        }
        String s = Double.toString(v);
        if (s.contains("E")) {
            // Python 用 e+XX 形式，这里项目内不会出现，简单兜底
            s = s.replace("E", "e");
        }
        return s;
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        // ensure_ascii=False：中文原样输出
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }
}
