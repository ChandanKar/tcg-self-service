package com.tcgdigital.vmcontrol.service.assistant;

import java.util.Map;

/**
 * A read-only tool an AI assistant may call through the MCP endpoint (E36). Tools run as the
 * token's owner and return only what that user may see; none may start, stop or change anything.
 */
public interface AssistantTool {

    /** The tool's MCP name, e.g. {@code whoami}. */
    String name();

    /** What the tool does, for the model choosing it. */
    String description();

    /** The JSON Schema of its arguments (an object schema). */
    Map<String, Object> inputSchema();

    /** Runs the tool; the result is returned to the client as JSON. Throw IllegalArgumentException for bad arguments. */
    Object call(Map<String, Object> arguments);
}
