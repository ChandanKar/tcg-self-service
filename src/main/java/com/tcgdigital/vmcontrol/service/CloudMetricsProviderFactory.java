package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.model.CloudProvider;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class CloudMetricsProviderFactory {

    private final Map<CloudProvider, CloudMetricsProviderService> services = new HashMap<>();

    public CloudMetricsProviderFactory(List<CloudMetricsProviderService> providers) {
        for (CloudMetricsProviderService provider : providers) {
            services.put(provider.getProvider(), provider);
        }
    }

    public Optional<CloudMetricsProviderService> getService(CloudProvider provider) {
        return Optional.ofNullable(services.get(provider));
    }
}
