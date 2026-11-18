/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicwarez.opendevtrack.TestInfrastructure;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The metrics for the alerts (ADR-24): published for Prometheus on the management port only, with the refusals by
 * status and the OIDC logins by outcome.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
class MetricsTest {

    private static final int MANAGEMENT = freePort();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("management.server.port", () -> MANAGEMENT);
    }

    @LocalServerPort
    int port;

    @Autowired
    MeterRegistry metrics;

    @Autowired
    LoginMetrics logins;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void theMetricsAreOnTheManagementPortOnly() throws Exception {
        assertThat(get(port, "/api/projects").statusCode()).isEqualTo(401);
        assertThat(get(port, "/actuator/prometheus").statusCode()).isEqualTo(401);

        HttpResponse<String> scraped = get(MANAGEMENT, "/actuator/prometheus");
        assertThat(scraped.statusCode()).isEqualTo(200);
        assertThat(scraped.body()).contains("odt_access_refused_total{status=\"401\"}");
    }

    @Test
    void onlyTheOidcLoginsAreCounted() {
        double before = count("success");
        logins.succeeded(new AuthenticationSuccessEvent(oidcLogin()));
        logins.succeeded(new AuthenticationSuccessEvent(new TestingAuthenticationToken("tool", "token")));
        logins.failed(new AuthenticationFailureBadCredentialsEvent(oidcLogin(), new BadCredentialsException("state")));

        assertThat(count("success")).isEqualTo(before + 1);
        assertThat(count("failure")).isGreaterThanOrEqualTo(1);
    }

    private double count(String outcome) {
        var counter = metrics.find("odt.logins").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private static OAuth2LoginAuthenticationToken oidcLogin() {
        ClientRegistration client = ClientRegistration.withRegistrationId("iam").clientId("odt")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("http://localhost/cb")
                .authorizationUri("http://iam/auth").tokenUri("http://iam/token").build();
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri("http://iam/auth").clientId("odt").redirectUri("http://localhost/cb").state("s")
                .attributes(Map.of()).build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success("code").state("s")
                .redirectUri("http://localhost/cb").build();
        return new OAuth2LoginAuthenticationToken(client, new OAuth2AuthorizationExchange(request, response));
    }

    private HttpResponse<String> get(int on, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + on + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
