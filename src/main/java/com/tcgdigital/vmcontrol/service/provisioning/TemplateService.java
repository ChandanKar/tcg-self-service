package com.tcgdigital.vmcontrol.service.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.tcgdigital.vmcontrol.dto.EnvironmentTemplateDTO;
import com.tcgdigital.vmcontrol.dto.UpsertEnvironmentTemplateDTO;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import com.tcgdigital.vmcontrol.model.ProvisioningEngineType;
import com.tcgdigital.vmcontrol.repository.EnvironmentTemplateRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The environment template catalogue (E32-T01): administrators add, change and retire templates;
 * everyone sees the active templates their role may request. The engine reference and the
 * parameter schema are checked on every write. Nothing is reachable while
 * {@code provisioning.enabled} is off (404).
 */
@Service
public class TemplateService {

    static final Set<String> ROLES = Set.of("ENV_ADMIN", "USER", "VIEWER");
    static final Set<String> APPROVER_ROLES = Set.of("ADMIN", "ENV_ADMIN");
    static final Set<String> TYPES = Set.of("string", "number", "enum");
    private static final Pattern KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    static final int MAX_TTL_HOURS = 24 * 90;

    private final EnvironmentTemplateRepository repository;
    private final SecurityService securityService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    @Value("${provisioning.enabled:false}")
    private boolean enabled;

    /** An admin's override from Settings (E29-T03), else the configured value; absent in plain unit tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.tcgdigital.vmcontrol.service.settings.SettingsService settings;

    public TemplateService(EnvironmentTemplateRepository repository, SecurityService securityService,
                           AuditService auditService, ObjectMapper objectMapper) {
        this.repository = repository;
        this.securityService = securityService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return settings == null ? enabled : settings.overrideOr("provisioning.enabled", enabled);
    }

    /** 404 while provisioning is off: the whole feature is invisible. */
    public void requireEnabled() {
        if (!isEnabled()) {
            throw new ResourceNotFoundException("Provisioning is not enabled");
        }
    }

    /** Active templates the caller's role may request; administrators may ask for retired ones too. */
    public List<EnvironmentTemplateDTO> list(boolean includeRetired) {
        requireEnabled();
        boolean admin = securityService.isAdmin();
        List<EnvironmentTemplate> all = admin && includeRetired ? repository.findAllByOrderByName() : repository.findByActiveTrueOrderByName();
        String role = callerRole();
        return all.stream().filter(t -> admin || allowed(t).contains(role)).map(t -> toDto(t, admin)).toList();
    }

    public EnvironmentTemplateDTO get(String templateId) {
        requireEnabled();
        EnvironmentTemplate t = find(templateId);
        boolean admin = securityService.isAdmin();
        if (!admin && (!t.isActive() || !allowed(t).contains(callerRole()))) {
            throw new ResourceNotFoundException("Template", templateId);
        }
        return toDto(t, admin);
    }

    /** The template a request may use: active and open to the caller's role (E32-T03). */
    public EnvironmentTemplate requestable(String templateId) {
        requireEnabled();
        EnvironmentTemplate t = find(templateId);
        if (!t.isActive() || (!securityService.isAdmin() && !allowed(t).contains(callerRole()))) {
            throw new ResourceNotFoundException("Template", templateId);
        }
        return t;
    }

    public EnvironmentTemplate find(String templateId) {
        return repository.findById(templateId).orElseThrow(() -> new ResourceNotFoundException("Template", templateId));
    }

    @Transactional
    public EnvironmentTemplateDTO create(UpsertEnvironmentTemplateDTO dto, String userId) {
        requireEnabled();
        if (repository.findByNameIgnoreCase(dto.name().trim()).isPresent()) {
            throw new ValidationException("A template named '" + dto.name().trim() + "' already exists");
        }
        EnvironmentTemplate t = new EnvironmentTemplate();
        apply(t, dto);
        t.setCreatedBy(userId);
        t = repository.save(t);
        auditService.logAction(userId, AuditAction.TEMPLATE_CREATED, "template", t.getTemplateId(), t.getName(),
                "Engine " + t.getEngine() + ", for " + t.getAllowedRoles());
        return toDto(t, true);
    }

    @Transactional
    public EnvironmentTemplateDTO update(String templateId, UpsertEnvironmentTemplateDTO dto, String userId) {
        requireEnabled();
        EnvironmentTemplate t = find(templateId);
        repository.findByNameIgnoreCase(dto.name().trim())
                .filter(other -> !other.getTemplateId().equals(templateId))
                .ifPresent(other -> { throw new ValidationException("A template named '" + dto.name().trim() + "' already exists"); });
        apply(t, dto);
        t = repository.save(t);
        auditService.logAction(userId, AuditAction.TEMPLATE_UPDATED, "template", t.getTemplateId(), t.getName(), "Template changed");
        return toDto(t, true);
    }

    /** Retired templates stay for the requests that used them, but cannot be requested again. */
    @Transactional
    public EnvironmentTemplateDTO retire(String templateId, String userId) {
        requireEnabled();
        EnvironmentTemplate t = find(templateId);
        if (t.isActive()) {
            t.setActive(false);
            t = repository.save(t);
            auditService.logAction(userId, AuditAction.TEMPLATE_RETIRED, "template", t.getTemplateId(), t.getName(), "Template retired");
        }
        return toDto(t, true);
    }

    private void apply(EnvironmentTemplate t, UpsertEnvironmentTemplateDTO dto) {
        ProvisioningEngineType engine;
        try {
            engine = ProvisioningEngineType.valueOf(dto.engine().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("engine must be SERVICE_CATALOG or TERRAFORM");
        }
        validateEngineRef(engine, dto.engineRef());
        JsonNode schema = dto.parameterSchema() == null || dto.parameterSchema().isNull()
                ? JsonNodeFactory.instance.arrayNode() : dto.parameterSchema();
        validateSchema(schema);
        int defaultTtl = dto.defaultTtlHours() == null ? 72 : dto.defaultTtlHours();
        int maxTtl = dto.maxTtlHours() == null ? Math.max(336, defaultTtl) : dto.maxTtlHours();
        if (defaultTtl < 1 || maxTtl < defaultTtl || maxTtl > MAX_TTL_HOURS) {
            throw new ValidationException("Lifetime must be at least 1 hour, the maximum at least the default and at most "
                    + MAX_TTL_HOURS + " hours");
        }
        String approver = dto.approverRole() == null ? "ADMIN" : dto.approverRole().trim().toUpperCase(Locale.ROOT);
        if (!APPROVER_ROLES.contains(approver)) {
            throw new ValidationException("approverRole must be ADMIN or ENV_ADMIN");
        }
        List<String> roles = dto.allowedRoles() == null || dto.allowedRoles().isEmpty() ? List.of("ENV_ADMIN", "USER")
                : dto.allowedRoles().stream().map(r -> r.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        if (!ROLES.containsAll(roles)) {
            throw new ValidationException("allowedRoles may contain ENV_ADMIN, USER and VIEWER");
        }
        if (dto.estimatedMonthlyCost() != null && dto.estimatedMonthlyCost().signum() < 0) {
            throw new ValidationException("estimatedMonthlyCost cannot be negative");
        }
        t.setName(dto.name().trim());
        t.setDescription(dto.description());
        t.setEngine(engine);
        t.setEngineRef(dto.engineRef().toString());
        t.setParameterSchema(schema.toString());
        t.setAwsAccountRef(blankToNull(dto.awsAccountRef()));
        t.setRegion(blankToNull(dto.region()));
        t.setDefaultTtlHours(defaultTtl);
        t.setMaxTtlHours(maxTtl);
        t.setRequiresApproval(dto.requiresApproval() == null || dto.requiresApproval());
        t.setApproverRole(approver);
        t.setAllowedRoles(String.join(",", roles));
        t.setEstimatedMonthlyCost(dto.estimatedMonthlyCost());
    }

    static void validateEngineRef(ProvisioningEngineType engine, JsonNode ref) {
        if (ref == null || !ref.isObject()) {
            throw new ValidationException("engineRef must be a JSON object");
        }
        List<String> required = engine == ProvisioningEngineType.SERVICE_CATALOG
                ? List.of("productId", "provisioningArtifactId") : List.of("organization", "workspacePrefix");
        for (String key : required) {
            if (!ref.hasNonNull(key) || !ref.get(key).isTextual() || ref.get(key).asText().isBlank()) {
                throw new ValidationException("engineRef." + key + " is required for " + engine);
            }
        }
    }

    static void validateSchema(JsonNode schema) {
        if (!schema.isArray()) {
            throw new ValidationException("parameterSchema must be a JSON array");
        }
        Set<String> keys = new HashSet<>();
        for (JsonNode p : schema) {
            String key = p.path("key").asText("");
            if (!KEY.matcher(key).matches()) {
                throw new ValidationException("Parameter keys start with a letter and use letters, digits or _ ('" + key + "')");
            }
            if (!keys.add(key)) {
                throw new ValidationException("Parameter key '" + key + "' is used twice");
            }
            String type = p.path("type").asText("string");
            if (!TYPES.contains(type)) {
                throw new ValidationException("Parameter '" + key + "' has type '" + type + "'; use string, number or enum");
            }
            List<String> options = new ArrayList<>();
            if ("enum".equals(type)) {
                if (!p.path("options").isArray() || p.path("options").isEmpty()) {
                    throw new ValidationException("Enum parameter '" + key + "' needs options");
                }
                p.path("options").forEach(o -> options.add(o.asText()));
            }
            Pattern pattern = null;
            if (p.hasNonNull("pattern")) {
                try {
                    pattern = Pattern.compile(p.get("pattern").asText());
                } catch (PatternSyntaxException e) {
                    throw new ValidationException("Parameter '" + key + "' has an invalid pattern");
                }
            }
            if (p.hasNonNull("default")) {
                String value = p.get("default").asText();
                String problem = valueProblem(type, options, pattern, value);
                if (problem != null) {
                    throw new ValidationException("Default of '" + key + "' " + problem);
                }
            }
        }
    }

    /** Why a value does not fit a parameter, or null when it does (also used for requests, E32-T03). */
    static String valueProblem(String type, List<String> options, Pattern pattern, String value) {
        if ("number".equals(type)) {
            try {
                new java.math.BigDecimal(value.trim());
            } catch (NumberFormatException e) {
                return "must be a number";
            }
        }
        if ("enum".equals(type) && !options.contains(value)) {
            return "must be one of " + String.join(", ", options);
        }
        if (pattern != null && !pattern.matcher(value).matches()) {
            return "does not match " + pattern.pattern();
        }
        return null;
    }

    private String callerRole() {
        if (securityService.isAdmin()) {
            return "ADMIN";
        }
        return securityService.isEnvAdmin() ? "ENV_ADMIN" : "USER";
    }

    static List<String> allowed(EnvironmentTemplate t) {
        return t.getAllowedRoles() == null ? List.of() : Arrays.stream(t.getAllowedRoles().split(",")).map(String::trim).toList();
    }

    EnvironmentTemplateDTO toDto(EnvironmentTemplate t, boolean admin) {
        return new EnvironmentTemplateDTO(t.getTemplateId(), t.getName(), t.getDescription(), t.getEngine().name(),
                admin ? json(t.getEngineRef()) : null, json(t.getParameterSchema()), t.getAwsAccountRef(), t.getRegion(),
                t.getDefaultTtlHours(), t.getMaxTtlHours(), t.isRequiresApproval(), t.getApproverRole(), allowed(t),
                t.getEstimatedMonthlyCost(), t.isActive(), t.getCreatedAt(), t.getUpdatedAt());
    }

    private JsonNode json(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
