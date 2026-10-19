package com.tcgdigital.vmcontrol.dto.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.tcgdigital.vmcontrol.security.ProviderIdVisibility;
import com.tcgdigital.vmcontrol.service.support.ProviderIdRedactor;

import java.io.IOException;

/** Writes {@link RedactProviderIds} text as stored for administrators and with ids masked for everyone else. */
public class RedactingStringSerializer extends StdSerializer<String> {

    public RedactingStringSerializer() {
        super(String.class);
    }

    @Override
    public void serialize(String value, JsonGenerator generator, SerializerProvider provider) throws IOException {
        generator.writeString(ProviderIdVisibility.currentViewerMaySee() ? value : ProviderIdRedactor.redact(value));
    }
}
