/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The key in the transit engine of Vault or OpenBao (ADR-24 point 5, ASVS 6.4.2): the back end sends each value to
 * {@code POST /v1/<mount>/encrypt/<key>} and {@code …/decrypt/<key>} over HTTP, and the key never leaves the service.
 * A value is kept as {@code transit:} followed by the ciphertext of Vault ({@code vault:v<version>:…}), which names
 * the version of the key: Vault decrypts the older versions after a rotation until its {@code min_decryption_version}
 * says otherwise. No SDK.
 *
 * <p>The token comes from {@code odt.secrets.transit.token-file}, read again at each call so that the platform can
 * renew it (for example the file of a Vault agent), or from {@code odt.secrets.transit.token}. It needs only the
 * {@code update} capability on the two paths.
 */
final class TransitKeys implements SecretKeys {

    static final String PREFIX = "transit:";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final URI encrypt;
    private final URI decrypt;
    private final String token;
    private final Path tokenFile;
    private final HttpClient http;

    TransitKeys(String address, String mount, String key, String token, String tokenFile) {
        if (address == null || address.isBlank() || key == null || key.isBlank()) {
            throw new IllegalStateException("odt.secrets.transit needs an address and a key name");
        }
        if ((token == null || token.isBlank()) && (tokenFile == null || tokenFile.isBlank())) {
            throw new IllegalStateException("odt.secrets.transit needs a token or a token-file");
        }
        String base = address.replaceAll("/+$", "") + "/v1/" + mount.replaceAll("^/+|/+$", "") + "/";
        this.encrypt = URI.create(base + "encrypt/" + key);
        this.decrypt = URI.create(base + "decrypt/" + key);
        this.token = token;
        this.tokenFile = tokenFile == null || tokenFile.isBlank() ? null : Path.of(tokenFile);
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public String seal(String plain) {
        String encoded = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        return PREFIX + call(encrypt, Map.of("plaintext", encoded), "ciphertext");
    }

    @Override
    public String open(String stored) {
        String plain = call(decrypt, Map.of("ciphertext", stored.substring(PREFIX.length())), "plaintext");
        return new String(Base64.getDecoder().decode(plain), StandardCharsets.UTF_8);
    }

    @Override
    public boolean current(String stored) {
        return stored.startsWith(PREFIX);
    }

    private String call(URI uri, Map<String, String> body, String field) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("X-Vault-Token", token())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("The transit engine is not reachable: " + uri.getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while calling the transit engine", e);
        }
        if (response.statusCode() != 200) {
            // The body of an error names what is wrong (a missing key, a denied path), never the value sent
            throw new IllegalStateException("The transit engine answered " + response.statusCode() + " on "
                    + uri.getPath() + ": " + response.body());
        }
        JsonNode value = JSON.readTree(response.body()).path("data").path(field);
        if (!value.isString()) {
            throw new IllegalStateException("The transit engine gave no " + field + " on " + uri.getPath());
        }
        return value.asString();
    }

    private String token() {
        if (tokenFile == null) {
            return token;
        }
        try {
            return Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException("The token file of the transit engine cannot be read: " + tokenFile, e);
        }
    }
}
