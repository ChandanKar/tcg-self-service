package com.tcgdigital.vmcontrol.service;

import com.tcgdigital.vmcontrol.dto.DirectoryUserDTO;
import com.tcgdigital.vmcontrol.exception.DirectoryLookupException;
import com.tcgdigital.vmcontrol.exception.ValidationException;
import com.tcgdigital.vmcontrol.model.User;
import com.tcgdigital.vmcontrol.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.GET;

@ExtendWith(MockitoExtension.class)
class GraphDirectoryServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private ObjectProvider<RestClient> restClientProvider;

    private GraphDirectoryService service;
    private MockRestServiceServer server;

    private static final String TWO_USERS_JSON = """
            {
              "@odata.context": "https://graph.microsoft.com/v1.0/$metadata#users",
              "value": [
                { "id": "oid-1", "displayName": "Bob One",  "mail": "bob.one@corp.com", "userPrincipalName": "bone@corp.com" },
                { "id": "oid-2", "displayName": "Bob Two",  "mail": null,               "userPrincipalName": "btwo@corp.com" }
              ]
            }
            """;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://graph.test/v1.0");
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        lenient().when(restClientProvider.getIfAvailable()).thenReturn(restClient);

        service = new GraphDirectoryService(restClientProvider, userRepository);
        ReflectionTestUtils.setField(service, "directoryEnabled", true);
    }

    @Test
    void search_disabledFlag_throwsConflict_andNeverCallsGraph() {
        ReflectionTestUtils.setField(service, "directoryEnabled", false);

        DirectoryLookupException ex = assertThrows(DirectoryLookupException.class,
                () -> service.search("bob", null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
        assertEquals("directory_lookup_disabled", ex.getErrorCode());
        server.verify(); // no expectations set → asserts nothing was sent
    }

    @Test
    void search_clientNotWired_throwsConflict() {
        when(restClientProvider.getIfAvailable()).thenReturn(null);

        DirectoryLookupException ex = assertThrows(DirectoryLookupException.class,
                () -> service.search("bob", null));

        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }

    @Test
    void search_shortQuery_returnsEmptyWithoutCallingGraph() {
        assertTrue(service.search(" a ", null).isEmpty());
        server.verify();
        verifyNoInteractions(userRepository);
    }

    @Test
    void search_happyPath_mapsFields_fallsBackToUpn_andFlagsExistingUsers() {
        User existing = new User("app-user-1");
        when(userRepository.findByAzureAdObjectId("oid-1")).thenReturn(Optional.of(existing));
        when(userRepository.findByAzureAdObjectId("oid-2")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("btwo@corp.com")).thenReturn(Optional.empty());

        server.expect(method(GET))
                .andExpect(header("ConsistencyLevel", "eventual"))
                .andRespond(withSuccess(TWO_USERS_JSON, MediaType.APPLICATION_JSON));

        List<DirectoryUserDTO> results = service.search("bob", null);

        assertEquals(2, results.size());

        DirectoryUserDTO one = results.get(0);
        assertEquals("oid-1", one.directoryObjectId());
        assertEquals("Bob One", one.displayName());
        assertEquals("bob.one@corp.com", one.email());
        assertTrue(one.alreadyInApp());
        assertEquals("app-user-1", one.appUserId());

        DirectoryUserDTO two = results.get(1);
        assertEquals("btwo@corp.com", two.email(), "mail is null → fall back to UPN");
        assertFalse(two.alreadyInApp());
        assertNull(two.appUserId());

        server.verify();
    }

    @Test
    void search_stripsQuotesFromTerm_andClampsTopTo25() throws Exception {
        server.expect(request -> {
                    String uri = URLDecoder.decode(request.getURI().toString(), StandardCharsets.UTF_8);
                    assertTrue(uri.contains("displayName:bob OR 1"), uri);
                    assertFalse(uri.contains("bob\" OR \"1"), "injected quotes must be stripped");
                    assertTrue(uri.contains("$top=25"), uri);
                })
                .andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));

        assertTrue(service.search("bob\" OR \"1", 999).isEmpty());
        server.verify();
    }

    @Test
    void search_graphReturnsError_throwsBadGateway() {
        server.expect(method(GET)).andRespond(withServerError());

        DirectoryLookupException ex = assertThrows(DirectoryLookupException.class,
                () -> service.search("bob", null));

        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
        assertEquals("directory_unavailable", ex.getErrorCode());
    }

    @Test
    void search_emptyValueArray_returnsEmptyList() {
        server.expect(method(GET)).andRespond(withSuccess("{\"value\":[]}", MediaType.APPLICATION_JSON));

        assertTrue(service.search("bob", null).isEmpty());
        verify(userRepository, never()).findByAzureAdObjectId(anyString());
    }

    @Test
    void isEnabled_reflectsFlagAndClientPresence() {
        assertTrue(service.isEnabled());

        when(restClientProvider.getIfAvailable()).thenReturn(null);
        assertFalse(service.isEnabled());
    }

    // ---- fetchByObjectId ----

    @Test
    void fetchByObjectId_happyPath_returnsDto() {
        String json = """
                { "id": "oid-1", "displayName": "Bob One", "mail": "bob.one@corp.com", "userPrincipalName": "bone@corp.com" }
                """;
        server.expect(method(GET)).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        DirectoryUserDTO dto = service.fetchByObjectId("oid-1");

        assertEquals("oid-1", dto.directoryObjectId());
        assertEquals("Bob One", dto.displayName());
        assertEquals("bob.one@corp.com", dto.email());
        server.verify();
    }

    @Test
    void fetchByObjectId_graph404_throwsValidation() {
        server.expect(method(GET)).andRespond(withResourceNotFound());

        assertThrows(ValidationException.class, () -> service.fetchByObjectId("missing"));
    }

    @Test
    void fetchByObjectId_graph500_throwsBadGateway() {
        server.expect(method(GET)).andRespond(withServerError());

        DirectoryLookupException ex = assertThrows(DirectoryLookupException.class,
                () -> service.fetchByObjectId("oid-1"));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatus());
    }

    @Test
    void fetchByObjectId_disabled_throwsConflict() {
        ReflectionTestUtils.setField(service, "directoryEnabled", false);

        DirectoryLookupException ex = assertThrows(DirectoryLookupException.class,
                () -> service.fetchByObjectId("oid-1"));
        assertEquals(HttpStatus.CONFLICT, ex.getStatus());
    }
}
