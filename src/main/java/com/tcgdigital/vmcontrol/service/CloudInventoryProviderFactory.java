package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CloudInventoryProviderFactory {

    private final Map<CloudProvider, CloudInventoryProviderService> services = new HashMap<>();

    public CloudInventoryProviderFactory(List<CloudInventoryProviderService> providers) {
        for (CloudInventoryProviderService provider : providers) {
            services.put(provider.getProvider(), provider);
        }
    }

    public Optional<CloudInventoryProviderService> getService(CloudProvider provider) {
        return Optional.ofNullable(services.get(provider));
    }
}
