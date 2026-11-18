/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The key in the transit engine (ADR-24 point 5): a small Vault of the test answers {@code encrypt} and
 * {@code decrypt} as the transit engine does, and checks the token. The values move from the local key to transit.
 */
class TransitKeysTest {

    private static final String LOCAL = "a-key-of-the-installation-of-32-characters";
    private static final Pattern FIELD = Pattern.compile("\"(plaintext|ciphertext)\"\\s*:\\s*\"([^\"]*)\"");

    private HttpServer vault;
    private final List<String> tokens = new ArrayList<>();

    @TempDir
    Path dir;

    @BeforeEach
    void start() throws IOException {
        vault = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        vault.createContext("/v1/transit/", exchange -> {
            tokens.add(exchange.getRequestHeaders().getFirst("X-Vault-Token"));
            Matcher m = FIELD.matcher(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath();
            String answer;
            int status = 200;
            if (!m.find() || !path.endsWith("/opendevtrack")) {
                status = 400;
                answer = "{\"errors\":[\"bad request\"]}";
            } else if (path.contains("/encrypt/")) {
                // Not a cipher: enough to see that the value went through the engine and comes back
                answer = "{\"data\":{\"ciphertext\":\"vault:v1:" + new StringBuilder(m.group(2)).reverse() + "\"}}";
            } else {
                String sealed = m.group(2).substring("vault:v1:".length());
                answer = "{\"data\":{\"plaintext\":\"" + new StringBuilder(sealed).reverse() + "\"}}";
            }
            byte[] body = answer.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        vault.start();
    }

    @AfterEach
    void stop() {
        vault.stop(0);
    }

    private String address() {
        return "http://127.0.0.1:" + vault.getAddress().getPort();
    }

    @Test
    void sealsAndOpensThroughTheEngine() throws IOException {
        Path token = Files.writeString(dir.resolve("token"), "s.file-token\n");
        SecretBox box = new SecretBox("transit", "", "1", "", address(), "transit", "opendevtrack", "", token.toString());

        String sealed = box.seal("whsec_abc");
        assertThat(sealed).startsWith("transit:vault:v1:").doesNotContain("whsec_");
        assertThat(box.open(sealed)).isEqualTo("whsec_abc");
        assertThat(box.stale(sealed)).isFalse();
        assertThat(tokens).containsOnly("s.file-token");
        assertThat(Base64.getDecoder().decode(new StringBuilder(sealed.substring("transit:vault:v1:".length()))
                .reverse().toString())).asString().isEqualTo("whsec_abc");
    }

    @Test
    void movesTheValuesOfTheLocalKey() {
        String local = new SecretBox(LOCAL, "1", "").seal("whsec_abc");
        SecretBox box = new SecretBox("transit", "", "1", "1=" + LOCAL, address(), "transit", "opendevtrack",
                "s.token", "");

        assertThat(box.open(local)).isEqualTo("whsec_abc");
        assertThat(box.stale(local)).isTrue();
        assertThat(box.stale("whsec_clear")).isTrue();
        String moved = box.reseal(local).orElseThrow();
        assertThat(moved).startsWith("transit:");
        assertThat(box.reseal(moved)).isEmpty();
        assertThat(box.open(moved)).isEqualTo("whsec_abc");
    }

    @Test
    void refusesWhatItCannotDo() {
        assertThatThrownBy(() -> new SecretBox("transit", "", "1", "", "", "transit", "opendevtrack", "s.t", ""))
                .hasMessageContaining("address");
        assertThatThrownBy(() -> new SecretBox("transit", "", "1", "", address(), "transit", "opendevtrack", "", ""))
                .hasMessageContaining("token");
        assertThatThrownBy(() -> new SecretBox("vault", LOCAL, "1", "", "", "", "", "", ""))
                .hasMessageContaining("local or transit");
        SecretBox wrongKey = new SecretBox("transit", "", "1", "", address(), "transit", "other", "s.t", "");
        assertThatThrownBy(() -> wrongKey.seal("x")).hasMessageContaining("400");
    }
}
