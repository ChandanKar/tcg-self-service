package com.tcgdigital.vmcontrol.dto.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.tcgdigital.vmcontrol.security.ProviderIdVisibility;

import java.io.IOException;

/**
 * Writes an {@link AdminOnly} property for administrators. For everyone else {@link #isEmpty} is true,
 * so the property is omitted; where a property cannot be omitted it is written as null.
 */
public class AdminOnlySerializer extends StdSerializer<Object> {

    public AdminOnlySerializer() {
        super(Object.class);
    }

    @Override
    public boolean isEmpty(SerializerProvider provider, Object value) {
        return value == null || !ProviderIdVisibility.currentViewerMaySee();
    }

    @Override
    public void serialize(Object value, JsonGenerator generator, SerializerProvider provider) throws IOException {
        if (!ProviderIdVisibility.currentViewerMaySee()) {
            generator.writeNull();
            return;
        }
        provider.defaultSerializeValue(value, generator);
    }
}
