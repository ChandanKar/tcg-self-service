package com.tcgdigital.vmcontrol.service.assistant;

import java.util.Map;
import java.util.function.Function;

/** An {@link AssistantTool} made of its parts, so a group of tools can be declared together. */
public record SimpleTool(String name, String description, Map<String, Object> inputSchema,
                         Function<Map<String, Object>, Object> body) implements AssistantTool {

    @Override
    public Object call(Map<String, Object> arguments) {
        return body.apply(arguments == null ? Map.of() : arguments);
    }
}
