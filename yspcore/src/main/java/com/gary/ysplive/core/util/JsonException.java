package com.gary.ysplive.core.util;

/** JSON 解析/序列化异常。 */
public class JsonException extends Exception {

    private static final long serialVersionUID = 1L;

    public JsonException(String message) {
        super(message);
    }

    public JsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
