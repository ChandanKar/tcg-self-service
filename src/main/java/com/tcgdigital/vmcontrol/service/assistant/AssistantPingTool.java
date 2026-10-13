package com.tcgdigital.vmcontrol.service.assistant;

import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.service.UserService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code whoami} (E36-T01): who the token belongs to, so a client can check its connection. */
@Component
public class AssistantPingTool implements AssistantTool {

    private final UserService userService;

    public AssistantPingTool(UserService userService) {
        this.userService = userService;
    }

    @Override
    public String name() {
        return "whoami";
    }

    @Override
    public String description() {
        return "Returns the name and role of the user this connection acts for.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(), "additionalProperties", false);
    }

    @Override
    public Object call(Map<String, Object> arguments) {
        User user = userService.getCurrentUser();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", user == null ? null : user.getDisplayName());
        result.put("email", user == null ? null : user.getEmail());
        result.put("role", user == null ? null : user.isAdmin() ? "ADMIN" : user.isEnvAdmin() ? "ENV_ADMIN" : "USER");
        result.put("serviceAccount", user != null && user.isServiceAccount());
        return result;
    }
}
