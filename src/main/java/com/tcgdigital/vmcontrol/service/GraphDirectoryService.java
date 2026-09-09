package com.tcgdigital.vmcontrol.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tcgdigital.vmcontrol.dto.DirectoryUserDTO;
import com.tcgdigital.vmcontrol.exception.DirectoryLookupException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Looks up Microsoft Entra ID directory users for the admin onboarding picker
 * ({@code GET /api/v1/directory/search}). See {@code docs/graph-user-onboarding.md}.
 *
 * <p>Enabled only when {@code graph.directory.enabled=true} and the {@link RestClient}
 * from {@code GraphConfig} is present; otherwise every call raises
 * {@link DirectoryLookupException#disabled()} and the caller onboards manually.
 */
@Service
public class GraphDirectoryService {

    private static final Logger log = LoggerFactory.getLogger(GraphDirectoryService.class);

    private static final int MIN_QUERY_LENGTH = 2;
    private static final int DEFAULT_TOP = 15;
    private static final int MAX_TOP = 25;
    private static final String SELECT_FIELDS = "id,displayName,mail,userPrincipalName";

    private final ObjectProvider<RestClient> graphRestClientProvider;
    private final UserRepository userRepository;

    @Value("${graph.directory.enabled:false}")
    private boolean directoryEnabled;

    public GraphDirectoryService(@Qualifier("graphRestClient") ObjectProvider<RestClient> graphRestClientProvider,
                                 UserRepository userRepository) {
        this.graphRestClientProvider = graphRestClientProvider;
        this.userRepository = userRepository;
    }

    /** True when a directory search would actually reach Graph (flag on + client wired). */
    public boolean isEnabled() {
        return directoryEnabled && graphRestClientProvider.getIfAvailable() != null;
    }

    /**
     * Search the directory by name / email / UPN (infix, via Graph {@code $search}).
     *
     * @param rawQuery user-typed term; quotes / control chars are stripped
     * @param topParam max results, clamped to [1, {@value #MAX_TOP}] (default {@value #DEFAULT_TOP})
     * @return matching directory users, each flagged whether they already have an {@code app_user}
     * @throws DirectoryLookupException 409 when disabled, 502 when Graph is unreachable
     */
    public List<DirectoryUserDTO> search(String rawQuery, Integer topParam) {
        if (!directoryEnabled) {
            throw DirectoryLookupException.disabled();
        }
        RestClient client = graphRestClientProvider.getIfAvailable();
        if (client == null) {
            throw DirectoryLookupException.disabled();
        }

        String query = sanitize(rawQuery);
        if (query.length() < MIN_QUERY_LENGTH) {
            return List.of();
        }
        int top = topParam == null ? DEFAULT_TOP : Math.max(1, Math.min(MAX_TOP, topParam));

        String search = "\"displayName:" + query + "\" OR \"mail:" + query
                + "\" OR \"userPrincipalName:" + query + "\"";

        GraphUsersResponse response;
        try {
            response = client.get()
                    .uri(uri -> uri.path("/users")
                            .queryParam("$search", search)
                            .queryParam("$select", SELECT_FIELDS)
                            .queryParam("$top", top)
                            .build())
                    .header("ConsistencyLevel", "eventual")
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(GraphUsersResponse.class);
        } catch (RestClientException e) {
            log.warn("Graph directory search failed for term of length {}: {}", query.length(), e.getMessage());
            throw DirectoryLookupException.unavailable(e.getMessage());
        }

        if (response == null || response.value() == null) {
            return List.of();
        }

        List<DirectoryUserDTO> results = new ArrayList<>(response.value().size());
        for (GraphUser gu : response.value()) {
            if (gu.id() == null) {
                continue;
            }
            String email = gu.mail() != null ? gu.mail() : gu.userPrincipalName();
            Optional<User> existing = userRepository.findByAzureAdObjectId(gu.id());
            if (existing.isEmpty() && email != null) {
                existing = userRepository.findByEmail(email);
            }
            results.add(new DirectoryUserDTO(
                    gu.id(),
                    gu.displayName(),
                    email,
                    gu.userPrincipalName(),
                    existing.isPresent(),
                    existing.map(User::getUserId).orElse(null)));
        }
        return results;
    }

    /**
     * Re-fetch one directory user by Entra object id, used by onboarding to trust
     * server-side data rather than the request body.
     *
     * @throws DirectoryLookupException disabled() when lookup is off; unavailable() on a Graph error
     * @throws ValidationException      when the id is unknown to the directory
     */
    public DirectoryUserDTO fetchByObjectId(String directoryObjectId) {
        if (!directoryEnabled) {
            throw DirectoryLookupException.disabled();
        }
        RestClient client = graphRestClientProvider.getIfAvailable();
        if (client == null) {
            throw DirectoryLookupException.disabled();
        }

        GraphUser gu;
        try {
            gu = client.get()
                    .uri(uri -> uri.path("/users/{id}")
                            .queryParam("$select", SELECT_FIELDS)
                            .build(directoryObjectId))
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(GraphUser.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                throw new ValidationException("No directory user with id " + directoryObjectId);
            }
            log.warn("Graph fetch of user {} failed: {}", directoryObjectId, e.getMessage());
            throw DirectoryLookupException.unavailable(e.getMessage());
        } catch (RestClientException e) {
            log.warn("Graph fetch of user {} failed: {}", directoryObjectId, e.getMessage());
            throw DirectoryLookupException.unavailable(e.getMessage());
        }

        if (gu == null || gu.id() == null) {
            throw new ValidationException("No directory user with id " + directoryObjectId);
        }
        String email = gu.mail() != null ? gu.mail() : gu.userPrincipalName();
        return new DirectoryUserDTO(gu.id(), gu.displayName(), email, gu.userPrincipalName(), false, null);
    }

    /** Strip characters that would break out of the {@code $search} token or the request. */
    private String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("[\"\\\\\\p{Cntrl}]", "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GraphUsersResponse(List<GraphUser> value) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GraphUser(String id, String displayName, String mail, String userPrincipalName) {
    }
}
