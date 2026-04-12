package io.docpilot.processor.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a document conversion fails for an expected reason
 * (unsupported format, corrupt file, size limit, etc.).
 */
@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class ConversionException extends RuntimeException {

    public ConversionException(String message) {
        super(message);
    }

    public ConversionException(String message, Throwable cause) {
        super(message, cause);
    }
}
