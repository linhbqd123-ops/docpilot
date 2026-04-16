package io.docpilot.mcp.exception;

import java.util.List;

public class McpToolException extends RuntimeException {

    private final int rpcCode;
    private final int httpStatus;
    private final String errorCode;
    private final String userMessage;
    private final List<String> items;
    private final boolean retryable;

    private McpToolException(
        int rpcCode,
        int httpStatus,
        String errorCode,
        String userMessage,
        List<String> items,
        boolean retryable,
        Throwable cause
    ) {
        super(userMessage, cause);
        this.rpcCode = rpcCode;
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.userMessage = userMessage;
        this.items = items == null ? List.of() : List.copyOf(items);
        this.retryable = retryable;
    }

    public static McpToolException invalidParams(String errorCode, String userMessage) {
        return new McpToolException(-32602, 400, errorCode, userMessage, List.of(), false, null);
    }

    public static McpToolException notFound(String errorCode, String userMessage) {
        return new McpToolException(-32004, 404, errorCode, userMessage, List.of(), false, null);
    }

    public static McpToolException conflict(String errorCode, String userMessage) {
        return new McpToolException(-32009, 409, errorCode, userMessage, List.of(), false, null);
    }

    public static McpToolException internal(String errorCode, String userMessage, Throwable cause) {
        return new McpToolException(-32603, 502, errorCode, userMessage, List.of(), true, cause);
    }

    public int getRpcCode() {
        return rpcCode;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getUserMessage() {
        return userMessage;
    }

    public List<String> getItems() {
        return items;
    }

    public boolean isRetryable() {
        return retryable;
    }
}