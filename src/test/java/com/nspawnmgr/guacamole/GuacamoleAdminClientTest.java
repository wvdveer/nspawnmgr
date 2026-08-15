package com.nspawnmgr.guacamole;

import com.nspawnmgr.service.SettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.CONFLICT;

class GuacamoleAdminClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private GuacamoleAdminClient client;

    @BeforeEach
    void setUp() {
        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.guacamoleBaseUrl()).thenReturn("http://guac.example");
        when(settingsService.guacamoleDataSource()).thenReturn("fake");

        GuacamoleTokenClient tokenClient = mock(GuacamoleTokenClient.class);
        when(tokenClient.loginAsAdmin()).thenReturn(new GuacamoleTokenClient.TokenResponse("tok", "fake"));

        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        client = new GuacamoleAdminClient(settingsService, tokenClient, restTemplate);
    }

    @Test
    void createOrGetUserSwallowsARealGuacamoleAlreadyExists400() {
        // Confirmed live on yoga: real Guacamole answers a duplicate-username create with 400 Bad
        // Request (not 409 Conflict) and a JSON body whose "message" says "already exists" - two
        // concurrent ProvisioningService.provision() runs for the same owner's first two
        // containers race ShareService.ensureGuacamoleUser's non-atomic null-check into exactly
        // this call twice.
        server.expect(requestTo("http://guac.example/api/session/data/fake/users?token=tok"))
                .andExpect(method(POST))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"User \\\"ward\\\" already exists.\",\"type\":\"BAD_REQUEST\"}"));

        assertThatCode(() -> client.createOrGetUser("ward", "pw")).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void createOrGetUserStillSwallowsA409Conflict() {
        server.expect(requestTo("http://guac.example/api/session/data/fake/users?token=tok"))
                .andExpect(method(POST))
                .andRespond(withStatus(CONFLICT));

        assertThatCode(() -> client.createOrGetUser("ward", "pw")).doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void createOrGetUserRethrowsAnUnrelated400() {
        server.expect(requestTo("http://guac.example/api/session/data/fake/users?token=tok"))
                .andExpect(method(POST))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"Username must not be blank.\",\"type\":\"BAD_REQUEST\"}"));

        assertThatThrownBy(() -> client.createOrGetUser("", "pw"))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
        server.verify();
    }

    @Test
    void createOrGetUserSucceedsOnFirstCreate() {
        server.expect(requestTo("http://guac.example/api/session/data/fake/users?token=tok"))
                .andExpect(method(POST))
                .andRespond(withSuccess());

        assertThatCode(() -> client.createOrGetUser("ward", "pw")).doesNotThrowAnyException();
        server.verify();
    }
}
