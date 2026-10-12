package com.tcgdigital.vmcontrol.service.provisioning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tcgdigital.vmcontrol.dto.CreateProvisioningRequestDTO;
import com.tcgdigital.vmcontrol.dto.ProvisioningRequestDTO;
import com.tcgdigital.vmcontrol.exception.ConflictException;
import com.tcgdigital.vmcontrol.exception.ResourceNotFoundException;
import com.tcgdigital.vmcontrol.exception.UnauthorizedException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.AuditAction;
import com.tcgdigital.vmcontrol.model.EnvironmentTemplate;
import com.tcgdigital.vmcontrol.model.ProvisioningRequest;
import com.tcgdigital.vmcontrol.model.ProvisioningStatus;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.EnvironmentRepository;
import com.tcgdigital.vmcontrol.repository.ProvisioningRequestRepository;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import com.tcgdigital.vmcontrol.service.AuditService;
import com.tcgdigital.vmcontrol.service.NotificationService;
import com.tcgdigital.vmcontrol.service.SecurityService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Requests for an environment from a template (E32-T03): checked (name, parameters against the
 * template's schema, lifetime), then waiting for an approver of the template's approver role, or
 * approved at once when the template needs no approval. Requesters may cancel while it waits;
 * nobody approves their own request. Decisions are conditional on the request still waiting, so
 * two approvers never both decide (409).
 */
@Service
public class ProvisioningRequestService {

    /** Names end up as Service Catalog product names and tags: letters, digits, space, '.', '_', '-'. */
    static final Pattern NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ._-]{1,62}$");
    static final Set<ProvisioningStatus> OPEN = Set.of(ProvisioningStatus.PENDING_APPROVAL, ProvisioningStatus.APPROVED,
            ProvisioningStatus.PROVISIONING, ProvisioningStatus.READY, ProvisioningStatus.DESTROYING);

    private final ProvisioningRequestRepository repository;
    private final TemplateService templateService;
    private final EnvironmentRepository environmentRepository;
    private final UserRepository userRepository;
    private final SecurityService securityService;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final com.tcgdigital.vmcontrol.service.UserService userService;
    private NotificationService notificationService;
    private com.tcgdigital.vmcontrol.service.support.AfterCommit afterCommit;

    public ProvisioningRequestService(ProvisioningRequestRepository repository, TemplateService templateService,
                                      EnvironmentRepository environmentRepository, UserRepository userRepository,
                                      SecurityService securityService, AuditService auditService, ObjectMapper objectMapper,
                                      com.tcgdigital.vmcontrol.service.UserService userService) {
        this.userService = userService;
        this.repository = repository;
        this.templateService = templateService;
        this.environmentRepository = environmentRepository;
        this.userRepository = userRepository;
        this.securityService = securityService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setNotificationService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setAfterCommit(com.tcgdigital.vmcontrol.service.support.AfterCommit afterCommit) {
        this.afterCommit = afterCommit;
    }

    private void later(Runnable r) {
        if (afterCommit != null) {
            afterCommit.run(r);
        } else {
            r.run();
        }
    }

    @Transactional
    public ProvisioningRequestDTO create(CreateProvisioningRequestDTO dto, String userId) {
        EnvironmentTemplate template = templateService.requestable(dto.templateId());
        String name = dto.environmentName().trim();
        if (!NAME.matcher(name).matches()) {
            throw new ValidationException("Environment names are 2 to 63 letters, digits, spaces, '.', '_' or '-', starting with a letter or digit");
        }
        // Environments are stored under their slug (EnvironmentService), so compare that.
        if (environmentRepository.existsByName(com.tcgdigital.vmcontrol.service.support.NameNormalizer.slug(name))
                || repository.existsByEnvironmentNameIgnoreCaseAndStatusIn(name, OPEN)) {
            throw new ValidationException("An environment named '" + name + "' already exists or is being requested");
        }
        int ttl = dto.ttlHours() == null ? template.getDefaultTtlHours() : dto.ttlHours();
        if (ttl < 1 || ttl > template.getMaxTtlHours()) {
            throw new ValidationException("ttlHours must be between 1 and " + template.getMaxTtlHours());
        }
        ObjectNode parameters = validateParameters(template, dto.parameters());

        ProvisioningRequest r = new ProvisioningRequest();
        r.setTemplateId(template.getTemplateId());
        r.setRequestedByUserId(userId);
        r.setEnvironmentName(name);
        r.setParameters(parameters.toString());
        r.setJustification(dto.justification());
        r.setTtlHours(ttl);
        boolean autoApproved = !template.isRequiresApproval();
        r.setStatus(autoApproved ? ProvisioningStatus.APPROVED : ProvisioningStatus.PENDING_APPROVAL);
        if (autoApproved) {
            r.setReviewedAt(Timestamp.from(Instant.now()));
            r.setReviewNotes("Approved automatically: the template needs no approval");
        }
        ProvisioningRequest saved = repository.save(r);
        auditService.logAction(userId, AuditAction.PROVISIONING_REQUESTED, "provisioning_request", saved.getRequestId(), name,
                "Requested from template " + template.getName() + " for " + ttl + " h"
                        + (autoApproved ? "; approved automatically" : ""));
        if (!autoApproved && notificationService != null) {
            List<String> approvers = approverIds(template, userId);
            String requester = displayName(userId);
            later(() -> notificationService.notifyProvisioningRequested(approvers, saved.getRequestId(), name, template.getName(), requester));
        }
        return toDto(saved, template);
    }

    /** The request's parameters checked against the template: known keys, required ones present, types, options and patterns. */
    ObjectNode validateParameters(EnvironmentTemplate template, JsonNode given) {
        JsonNode schema;
        try {
            schema = objectMapper.readTree(template.getParameterSchema() == null ? "[]" : template.getParameterSchema());
        } catch (Exception e) {
            throw new ValidationException("The template's parameter schema is not valid");
        }
        ObjectNode values = given != null && given.isObject() ? ((ObjectNode) given).deepCopy() : objectMapper.createObjectNode();
        if (given != null && !given.isNull() && !given.isObject()) {
            throw new ValidationException("parameters must be a JSON object");
        }
        Set<String> known = new HashSet<>();
        ObjectNode result = objectMapper.createObjectNode();
        for (JsonNode p : schema) {
            String key = p.path("key").asText();
            known.add(key);
            JsonNode value = values.get(key);
            boolean missing = value == null || value.isNull() || value.asText().isBlank();
            if (missing && p.hasNonNull("default")) {
                value = p.get("default");
                missing = false;
            }
            if (missing) {
                if (p.path("required").asBoolean(false)) {
                    throw new ValidationException("Parameter '" + key + "' is required");
                }
                continue;
            }
            if (!value.isValueNode()) {
                throw new ValidationException("Parameter '" + key + "' must be a single value");
            }
            List<String> options = new ArrayList<>();
            p.path("options").forEach(o -> options.add(o.asText()));
            Pattern pattern = p.hasNonNull("pattern") ? Pattern.compile(p.get("pattern").asText()) : null;
            String problem = TemplateService.valueProblem(p.path("type").asText("string"), options, pattern, value.asText());
            if (problem != null) {
                throw new ValidationException("Parameter '" + key + "' " + problem);
            }
            result.set(key, value);
        }
        for (Iterator<String> it = values.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!known.contains(key)) {
                throw new ValidationException("Parameter '" + key + "' is not part of this template");
            }
        }
        return result;
    }

    @Transactional
    public ProvisioningRequestDTO approve(String requestId, String userId, String notes) {
        return decide(requestId, userId, true, notes);
    }

    @Transactional
    public ProvisioningRequestDTO reject(String requestId, String userId, String notes) {
        if (notes == null || notes.isBlank()) {
            throw new ValidationException("Say why the request is rejected");
        }
        return decide(requestId, userId, false, notes);
    }

    private ProvisioningRequestDTO decide(String requestId, String userId, boolean approve, String notes) {
        templateService.requireEnabled();
        ProvisioningRequest r = find(requestId);
        EnvironmentTemplate template = templateService.find(r.getTemplateId());
        if (!canApprove(template)) {
            throw new UnauthorizedException("Only a " + template.getApproverRole() + " can decide this request");
        }
        if (userId.equals(r.getRequestedByUserId())) {
            throw new UnauthorizedException("You cannot decide your own request");
        }
        ProvisioningStatus to = approve ? ProvisioningStatus.APPROVED : ProvisioningStatus.REJECTED;
        Timestamp now = Timestamp.from(Instant.now());
        if (repository.decide(requestId, ProvisioningStatus.PENDING_APPROVAL, to, userId, now, notes) == 0) {
            throw new ConflictException("This request is no longer waiting for a decision");
        }
        auditService.logAction(userId, approve ? AuditAction.PROVISIONING_APPROVED : AuditAction.PROVISIONING_REJECTED,
                "provisioning_request", requestId, r.getEnvironmentName(), notes == null ? "" : notes);
        if (notificationService != null) {
            String requester = r.getRequestedByUserId();
            String envName = r.getEnvironmentName();
            later(() -> notificationService.notifyProvisioningDecision(requester, requestId, envName, approve, notes));
        }
        return toDto(find(requestId), template);
    }

    @Transactional
    public ProvisioningRequestDTO cancel(String requestId, String userId) {
        templateService.requireEnabled();
        ProvisioningRequest r = find(requestId);
        if (!userId.equals(r.getRequestedByUserId())) {
            throw new ResourceNotFoundException("Provisioning request", requestId);
        }
        if (repository.decide(requestId, ProvisioningStatus.PENDING_APPROVAL, ProvisioningStatus.CANCELLED, userId,
                Timestamp.from(Instant.now()), "Cancelled by the requester") == 0) {
            throw new ConflictException("Only a request waiting for approval can be cancelled");
        }
        auditService.logAction(userId, AuditAction.PROVISIONING_CANCELLED, "provisioning_request", requestId,
                r.getEnvironmentName(), "Cancelled by the requester");
        return toDto(find(requestId), templateService.find(r.getTemplateId()));
    }

    public Page<ProvisioningRequestDTO> mine(String userId, Pageable pageable) {
        templateService.requireEnabled();
        return page(repository.findByRequestedByUserIdOrderByCreatedAtDesc(userId), pageable);
    }

    /** Requests waiting for a decision the caller may make. */
    public Page<ProvisioningRequestDTO> pending(String userId, Pageable pageable) {
        templateService.requireEnabled();
        List<ProvisioningRequest> mine = repository.findByStatusOrderByCreatedAtAsc(ProvisioningStatus.PENDING_APPROVAL).stream()
                .filter(r -> !userId.equals(r.getRequestedByUserId()))
                .filter(r -> canApprove(templateService.find(r.getTemplateId())))
                .toList();
        return page(mine, pageable);
    }

    /** The requester and those who may approve it see a request; others get 404. */
    public ProvisioningRequestDTO get(String requestId, String userId) {
        templateService.requireEnabled();
        ProvisioningRequest r = find(requestId);
        EnvironmentTemplate template = templateService.find(r.getTemplateId());
        if (!userId.equals(r.getRequestedByUserId()) && !canApprove(template)) {
            throw new ResourceNotFoundException("Provisioning request", requestId);
        }
        return toDto(r, template);
    }

    private ProvisioningRequest find(String requestId) {
        return repository.findById(requestId).orElseThrow(() -> new ResourceNotFoundException("Provisioning request", requestId));
    }

    boolean canApprove(EnvironmentTemplate template) {
        return securityService.isAdmin() || ("ENV_ADMIN".equals(template.getApproverRole()) && securityService.isEnvAdmin());
    }

    private List<String> approverIds(EnvironmentTemplate template, String requesterId) {
        Set<String> ids = new LinkedHashSet<>();
        userRepository.findByAdminTrueAndIsActiveTrue().forEach(u -> ids.add(u.getUserId()));
        if ("ENV_ADMIN".equals(template.getApproverRole())) {
            userRepository.findByEnvAdminTrueAndIsActiveTrue().forEach(u -> ids.add(u.getUserId()));
        }
        ids.remove(requesterId);
        return List.copyOf(ids);
    }

    private String displayName(String userId) {
        return userId == null ? null : userRepository.findById(userId).map(User::getDisplayName).orElse(null);
    }

    private Page<ProvisioningRequestDTO> page(List<ProvisioningRequest> rows, Pageable pageable) {
        Map<String, EnvironmentTemplate> templates = new java.util.HashMap<>();
        int from = (int) Math.min(pageable.getOffset(), rows.size());
        int to = Math.min(from + pageable.getPageSize(), rows.size());
        List<ProvisioningRequestDTO> content = rows.subList(from, to).stream()
                .map(r -> toDto(r, templates.computeIfAbsent(r.getTemplateId(), templateService::find))).toList();
        return new PageImpl<>(content, pageable, rows.size());
    }

    ProvisioningRequestDTO toDto(ProvisioningRequest r, EnvironmentTemplate template) {
        JsonNode parameters;
        try {
            parameters = objectMapper.readTree(r.getParameters() == null ? "{}" : r.getParameters());
        } catch (Exception e) {
            parameters = objectMapper.createObjectNode();
        }
        boolean waiting = r.getStatus() == ProvisioningStatus.PENDING_APPROVAL;
        String currentUser = currentUserId();
        return new ProvisioningRequestDTO(r.getRequestId(), r.getTemplateId(), template == null ? null : template.getName(),
                r.getRequestedByUserId(), displayName(r.getRequestedByUserId()), r.getEnvironmentName(), parameters,
                r.getJustification(), r.getTtlHours(), r.getStatus().name(), displayName(r.getReviewedByUserId()), r.getReviewedAt(),
                r.getReviewNotes(), r.getEnvironmentId(), r.getDestroyAt(), r.getErrorMessage(), r.getCreatedAt(),
                waiting && template != null && currentUser != null && !currentUser.equals(r.getRequestedByUserId()) && canApprove(template),
                waiting && currentUser != null && currentUser.equals(r.getRequestedByUserId()));
    }

    private String currentUserId() {
        try {
            return userService.getCurrentUserId();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
