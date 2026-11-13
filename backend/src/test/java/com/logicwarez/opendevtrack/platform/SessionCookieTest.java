/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicwarez.opendevtrack.TestInfrastructure;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The session cookie outside the development profile (ASVS 3.4.4), as a real server writes it: the {@code __Host-}
 * prefix with what the browser asks of it (Secure, Path=/, no Domain), and HttpOnly and SameSite=Lax as before. The
 * start of a login is the first answer that opens a session.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(TestInfrastructure.class)
class SessionCookieTest {

    @LocalServerPort
    int port;

    @Test
    void hostPrefixedSecureOnTheWholeSite() throws Exception {
        HttpResponse<Void> answer = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/oauth2/authorization/iam")).build(),
                HttpResponse.BodyHandlers.discarding());

        List<String> cookies = answer.headers().allValues("Set-Cookie");
        assertThat(cookies).anySatisfy(cookie -> assertThat(cookie).startsWith("__Host-SESSION_ID=")
                .contains("; Path=/", "; Secure", "; HttpOnly", "; SameSite=Lax")
                .doesNotContainIgnoringCase("Domain="));
        assertThat(cookies).noneSatisfy(cookie -> assertThat(cookie).startsWith("SESSION"));
    }
}
