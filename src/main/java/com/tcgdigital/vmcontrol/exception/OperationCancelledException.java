package com.tcgdigital.vmcontrol.exception;

/**
 * Signals that an in-flight VM operation's execution was cancelled while a cloud provider call
 * was still polling for a terminal state. Thrown from an {@code OperationProgressListener}
 * callback to unwind out of that poll loop early, rather than waiting for its full timeout.
 */
public class OperationCancelledException extends RuntimeException {

    public OperationCancelledException(String message) {
        super(message);
    }
}
