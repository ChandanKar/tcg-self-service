package com.tcgdigital.vmcontrol.dto;

/**
 * An RDS DB instance or Aurora cluster offered for registration (E33-T02). {@code stoppable} is
 * false, with the reason, for databases RDS cannot stop (read replicas, Multi-AZ SQL Server,
 * Aurora Serverless v1); {@code registered} when a VM already has this providerVmId.
 */
public record RdsDatabaseInfoDTO(
        String providerVmId,
        String identifier,
        String kind,
        String engine,
        String engineVersion,
        String instanceClass,
        String status,
        boolean multiAz,
        boolean stoppable,
        String notStoppableReason,
        boolean registered
) {
    public RdsDatabaseInfoDTO withRegistered(boolean value) {
        return new RdsDatabaseInfoDTO(providerVmId, identifier, kind, engine, engineVersion, instanceClass, status,
                multiAz, stoppable, notStoppableReason, value);
    }
}
