package com.nspawnmgr.cli;

public class ContainerCliException extends RuntimeException {

    public ContainerCliException(String message) {
        super(message);
    }

    public ContainerCliException(String message, Throwable cause) {
        super(message, cause);
    }
}
