package com.gary.ysplive.core.jce;

/** JCE 负载格式错误（数据不足、类型未知等）。常量时间无关，仅用于内部解析。 */
public class JceFormatException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JceFormatException(String message) {
        super(message);
    }

    public JceFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
