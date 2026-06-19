package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.CloudProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Factory to get the appropriate CloudProviderService for a given provider.
 */
@Service
public class CloudProviderFactory {

    private static final Logger log = LoggerFactory.getLogger(CloudProviderFactory.class);

    private final Map<CloudProvider, CloudProviderService> providerServices = new HashMap<>();

    public CloudProviderFactory(List<CloudProviderService> services) {
        for (CloudProviderService service : services) {
            providerServices.put(service.getProvider(), service);
            log.info("Registered cloud provider service: {} (available: {})",
                    service.getProvider(), service.isAvailable());
        }
    }

    /**
     * Get the service for a specific cloud provider.
     */
    public CloudProviderService getService(CloudProvider provider) {
        CloudProviderService service = providerServices.get(provider);
        if (service == null) {
            throw new ValidationException("Cloud provider not yet supported: " + provider +
                    ". Currently only AWS and AWS_EKS are available.");
        }
        return service;
    }

    /**
     * Check if a provider is available.
     */
    public boolean isProviderAvailable(CloudProvider provider) {
        CloudProviderService service = providerServices.get(provider);
        return service != null && service.isAvailable();
    }
}

