package com.tcgdigital.vmcontrol.service.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import com.tcgdigital.vmcontrol.model.ProvisioningEngineType;
import com.tcgdigital.vmcontrol.model.ProvisioningRequest;
import com.tcgdigital.vmcontrol.service.support.NameNormalizer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * HCP Terraform / Terraform Enterprise as a provisioning engine (E32-T06). Terraform runs there,
 * not in this app. A template's engine reference names the organization (else the configured
 * one), a workspace prefix, and either a VCS repository (with its OAuth token id) or a no-code
 * module, plus an optional Terraform version and agent pool. Provisioning creates (or reuses) the
 * workspace '&lt;prefix&gt;&lt;environment name&gt;', sets its variables from the request and
 * queues an auto-applied run; destroying queues a destroy run and, if configured, deletes the
 * workspace once it is applied.
 */
@Service
@ConditionalOnProperty(name = "provisioning.terraform.enabled", havingValue = "true")
public class TerraformCloudEngine implements ProvisioningEngine {

    static final Set<String> FAILED = Set.of("errored", "canceled", "discarded", "force_canceled");
    static final Set<String> SUCCEEDED = Set.of("applied", "planned_and_finished");

    private final RestClient client;
    private final ObjectMapper objectMapper;
    private final String defaultOrganization;
    private final boolean deleteWorkspace;

    public TerraformCloudEngine(@Qualifier("terraformRestClient") RestClient client, ObjectMapper objectMapper,
                                @Value("${provisioning.terraform.organization:}") String defaultOrganization,
                                @Value("${provisioning.terraform.delete-workspace:false}") boolean deleteWorkspace) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.defaultOrganization = defaultOrganization;
        this.deleteWorkspace = deleteWorkspace;
    }

    @Override
    public ProvisioningEngineType type() {
        return ProvisioningEngineType.TERRAFORM;
    }

    private JsonNode ref(EnvironmentTemplate template) {
        try {
            return objectMapper.readTree(template.getEngineRef());
        } catch (Exception e) {
            throw new ValidationException("The engine reference is not valid JSON");
        }
    }

    private String organization(JsonNode ref) {
        String org = ref.path("organization").asText(defaultOrganization);
        if (org == null || org.isBlank()) {
            throw new ValidationException("No Terraform organization in the template or provisioning.terraform.organization");
        }
        return org;
    }

    /** Terraform workspace names: letters, digits, '-' and '_' only, at most 90. */
    static String workspaceName(String prefix, String environmentName) {
        String name = (prefix == null ? "" : prefix) + NameNormalizer.slug(environmentName);
        name = name.replaceAll("[^A-Za-z0-9_-]", "-");
        return name.length() > 90 ? name.substring(0, 90) : name;
    }

    @Override
    public String provision(ProvisioningRequest request, EnvironmentTemplate template) {
        JsonNode ref = ref(template);
        String org = organization(ref);
        String name = workspaceName(ref.path("workspacePrefix").asText(""), request.getEnvironmentName());
        String workspaceId = findWorkspace(org, name);
        if (workspaceId == null) {
            workspaceId = createWorkspace(org, name, ref, request);
        }
        setVariables(workspaceId, request);
        return queueRun(workspaceId, false, "Provisioning request " + request.getRequestId());
    }

    private String findWorkspace(String org, String name) {
        try {
            JsonNode found = client.get().uri("/api/v2/organizations/{org}/workspaces/{name}", org, name)
                    .retrieve().body(JsonNode.class);
            return found == null ? null : found.path("data").path("id").asText(null);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
    }

    private String createWorkspace(String org, String name, JsonNode ref, ProvisioningRequest request) {
        ObjectNode attributes = objectMapper.createObjectNode();
        attributes.put("name", name);
        attributes.put("auto-apply", true);
        ArrayNode tags = attributes.putArray("tag-names");
        tags.add("managed-by-vmcontrol");
        tags.add("provisioning-request-" + request.getRequestId());
        if (ref.hasNonNull("terraformVersion")) {
            attributes.put("terraform-version", ref.get("terraformVersion").asText());
        }
        if (ref.hasNonNull("agentPoolId")) {
            attributes.put("execution-mode", "agent");
            attributes.put("agent-pool-id", ref.get("agentPoolId").asText());
        }
        if (ref.hasNonNull("vcsRepoIdentifier")) {
            ObjectNode vcs = attributes.putObject("vcs-repo");
            vcs.put("identifier", ref.get("vcsRepoIdentifier").asText());
            if (ref.hasNonNull("oauthTokenId")) {
                vcs.put("oauth-token-id", ref.get("oauthTokenId").asText());
            }
            if (ref.hasNonNull("branch")) {
                vcs.put("branch", ref.get("branch").asText());
            }
        }
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode data = body.putObject("data");
        data.put("type", "workspaces");
        data.set("attributes", attributes);
        String uri = ref.hasNonNull("noCodeModuleId")
                ? "/api/v2/no-code-modules/" + ref.get("noCodeModuleId").asText() + "/workspaces"
                : "/api/v2/organizations/" + org + "/workspaces";
        JsonNode created = client.post().uri(uri).body(body).retrieve().body(JsonNode.class);
        return created.path("data").path("id").asText();
    }

    /** Request parameters plus environment_name, provisioning_request_id and tags, created or updated. */
    private void setVariables(String workspaceId, ProvisioningRequest request) {
        Map<String, String[]> wanted = new LinkedHashMap<>(); // key -> {value, hcl}
        try {
            JsonNode params = objectMapper.readTree(request.getParameters() == null ? "{}" : request.getParameters());
            for (Iterator<Map.Entry<String, JsonNode>> it = params.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                wanted.put(e.getKey(), new String[] {e.getValue().asText(), "false"});
            }
        } catch (Exception e) {
            throw new ValidationException("The request parameters are not valid JSON");
        }
        String envName = NameNormalizer.slug(request.getEnvironmentName());
        wanted.put("environment_name", new String[] {envName, "false"});
        wanted.put("provisioning_request_id", new String[] {request.getRequestId(), "false"});
        wanted.put("tags", new String[] {"{\"tcg:environment\" = \"" + envName + "\", \"tcg:managed-by\" = \"vmcontrol\", "
                + "\"tcg:provisioning-request\" = \"" + request.getRequestId() + "\"}", "true"});

        Map<String, String> existing = new LinkedHashMap<>();
        JsonNode vars = client.get().uri("/api/v2/workspaces/{id}/vars", workspaceId).retrieve().body(JsonNode.class);
        if (vars != null) {
            vars.path("data").forEach(v -> existing.put(v.path("attributes").path("key").asText(), v.path("id").asText()));
        }
        for (Map.Entry<String, String[]> e : wanted.entrySet()) {
            ObjectNode body = objectMapper.createObjectNode();
            ObjectNode data = body.putObject("data");
            data.put("type", "vars");
            ObjectNode attributes = data.putObject("attributes");
            attributes.put("key", e.getKey());
            attributes.put("value", e.getValue()[0]);
            attributes.put("category", "terraform");
            attributes.put("hcl", Boolean.parseBoolean(e.getValue()[1]));
            attributes.put("sensitive", false);
            String varId = existing.get(e.getKey());
            if (varId == null) {
                client.post().uri("/api/v2/workspaces/{id}/vars", workspaceId).body(body).retrieve().toBodilessEntity();
            } else {
                data.put("id", varId);
                client.patch().uri("/api/v2/workspaces/{id}/vars/{varId}", workspaceId, varId).body(body).retrieve().toBodilessEntity();
            }
        }
    }

    private String queueRun(String workspaceId, boolean destroy, String message) {
        ObjectNode body = objectMapper.createObjectNode();
        ObjectNode data = body.putObject("data");
        data.put("type", "runs");
        ObjectNode attributes = data.putObject("attributes");
        attributes.put("message", message);
        attributes.put("auto-apply", true);
        attributes.put("is-destroy", destroy);
        ObjectNode workspace = data.putObject("relationships").putObject("workspace").putObject("data");
        workspace.put("type", "workspaces");
        workspace.put("id", workspaceId);
        JsonNode run = client.post().uri("/api/v2/runs").body(body).retrieve().body(JsonNode.class);
        return run.path("data").path("id").asText();
    }

    @Override
    public ProvisioningProgress poll(EnvironmentTemplate template, String recordId) {
        JsonNode run = client.get().uri("/api/v2/runs/{id}", recordId).retrieve().body(JsonNode.class);
        JsonNode data = run.path("data");
        String status = data.path("attributes").path("status").asText("").toLowerCase(Locale.ROOT);
        String workspaceId = data.path("relationships").path("workspace").path("data").path("id").asText(null);
        if (SUCCEEDED.contains(status)) {
            if (data.path("attributes").path("is-destroy").asBoolean(false) && deleteWorkspace && workspaceId != null) {
                client.delete().uri("/api/v2/workspaces/{id}", workspaceId).retrieve().onStatus(HttpStatusCode::is4xxClientError,
                        (req, res) -> { /* already gone */ }).toBodilessEntity();
            }
            return new ProvisioningProgress(ProvisioningProgress.State.SUCCEEDED, workspaceId, "Run " + status);
        }
        if (FAILED.contains(status)) {
            return new ProvisioningProgress(ProvisioningProgress.State.FAILED, workspaceId, "Terraform run " + status);
        }
        return new ProvisioningProgress(ProvisioningProgress.State.IN_PROGRESS, workspaceId, status);
    }

    @Override
    public Map<String, String> outputs(EnvironmentTemplate template, String resourceId) {
        Map<String, String> outputs = new LinkedHashMap<>();
        JsonNode response = client.get().uri("/api/v2/workspaces/{id}/current-state-version-outputs", resourceId)
                .retrieve().body(JsonNode.class);
        if (response != null) {
            response.path("data").forEach(o -> {
                JsonNode a = o.path("attributes");
                if (!a.path("sensitive").asBoolean(false)) {
                    JsonNode value = a.path("value");
                    outputs.put(a.path("name").asText(), value.isValueNode() ? value.asText() : value.toString());
                }
            });
        }
        return outputs;
    }

    @Override
    public String destroy(ProvisioningRequest request, EnvironmentTemplate template) {
        return queueRun(request.getEngineResourceId(), true, "Destroy for provisioning request " + request.getRequestId());
    }
}
