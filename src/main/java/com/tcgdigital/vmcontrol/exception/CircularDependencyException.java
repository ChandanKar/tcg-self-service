package com.tcgdigital.vmcontrol.exception;

/**
 * Exception thrown when a circular dependency is detected.
 */
public class CircularDependencyException extends RuntimeException {

    public CircularDependencyException(String message) {
        super(message);
    }

    public CircularDependencyException(String message, Throwable cause) {
        super(message, cause);
    }
}

