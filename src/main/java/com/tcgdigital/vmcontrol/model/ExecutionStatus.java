package com.tcgdigital.vmcontrol.model;

/**
 * Status of an operation execution.
 */
public enum ExecutionStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED,
    PARTIAL_SUCCESS
}

