package com.itsonlybinary.miniboard;

/**
 * Base type for MiniBoard failures. Unchecked, because these surface almost
 * exclusively through {@code CompletableFuture} completion, where checked
 * exceptions add no value.
 *
 * <p>Thrown directly for handshake failures and for connection loss.
 */
public class MiniBoardException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MiniBoardException(String message) {
        super(message);
    }

    public MiniBoardException(String message, Throwable cause) {
        super(message, cause);
    }
}
