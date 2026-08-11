package com.itsonlybinary.miniboard;

import java.io.IOException;

/** A serial port operation failed. */
public class MiniBoardIoException extends MiniBoardException {

    private static final long serialVersionUID = 1L;

    public MiniBoardIoException(String message, IOException cause) {
        super(message, cause);
    }

    public MiniBoardIoException(String message) {
        super(message);
    }
}
