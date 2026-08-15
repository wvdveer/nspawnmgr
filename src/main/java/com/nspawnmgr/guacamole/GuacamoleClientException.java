package com.nspawnmgr.guacamole;

public class GuacamoleClientException extends RuntimeException {

    public GuacamoleClientException(String message) {
        super(message);
    }

    public GuacamoleClientException(String message, Throwable cause) {
        super(message, cause);
    }
}
