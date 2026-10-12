package com.tcgdigital.vmcontrol.service.provisioning;

import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.ProvisioningEngineType;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The provisioning engine of each type (E32-T02), as CloudProviderFactory does for providers. */
@Service
public class ProvisioningEngineRegistry {

    private final Map<ProvisioningEngineType, ProvisioningEngine> engines = new EnumMap<>(ProvisioningEngineType.class);

    public ProvisioningEngineRegistry(List<ProvisioningEngine> engines) {
        engines.forEach(e -> this.engines.put(e.type(), e));
    }

    public ProvisioningEngine get(ProvisioningEngineType type) {
        ProvisioningEngine engine = engines.get(type);
        if (engine == null) {
            throw new ValidationException("No provisioning engine for " + type);
        }
        return engine;
    }

    public boolean has(ProvisioningEngineType type) {
        return engines.containsKey(type);
    }
}
