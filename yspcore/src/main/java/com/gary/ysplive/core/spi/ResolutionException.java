package com.gary.ysplive.core.spi;

/** 解析失败。{@code recoverable} 为 true 表示刷新地址后可能成功，false 表示当前设备身份已失效。 */
public class ResolutionException extends Exception {

    private static final long serialVersionUID = 1L;

    public final boolean recoverable;

    public ResolutionException(String message) {
        this(message, true);
    }

    public ResolutionException(String message, boolean recoverable) {
        super(message);
        this.recoverable = recoverable;
    }

    public ResolutionException(String message, Throwable cause) {
        super(message, cause);
        this.recoverable = true;
    }

    @Override
    public String toString() {
        return getMessage() == null ? getClass().getSimpleName() : getMessage();
    }
}
