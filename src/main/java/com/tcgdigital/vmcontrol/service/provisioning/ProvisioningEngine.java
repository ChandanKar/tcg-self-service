package com.tcgdigital.vmcontrol.service.provisioning;

import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import com.tcgdigital.vmcontrol.model.ProvisioningEngineType;
import com.tcgdigital.vmcontrol.model.ProvisioningRequest;

import java.util.Map;

/**
 * Something that builds and destroys an environment from a template (E32-T02): AWS Service
 * Catalog or Terraform. Every call is keyed so a retry never builds or destroys twice.
 */
public interface ProvisioningEngine {

    ProvisioningEngineType type();

    /** Starts building; returns the engine's record (run) id. */
    String provision(ProvisioningRequest request, EnvironmentTemplate template);

    /** Where a provision or destroy run is. */
    ProvisioningProgress poll(EnvironmentTemplate template, String recordId);

    /** What the built environment exposes (stack outputs). */
    Map<String, String> outputs(EnvironmentTemplate template, String resourceId);

    /** Starts destroying what a request built; returns the destroy run's record id. */
    String destroy(ProvisioningRequest request, EnvironmentTemplate template);
}
