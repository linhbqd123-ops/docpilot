package io.docpilot.mcp.exception;

/**
 * Thrown when a requested resource (session, revision, document) is not found.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
