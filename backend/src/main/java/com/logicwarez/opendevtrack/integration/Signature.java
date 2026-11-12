/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The signature of Standard Webhooks (ADR-21, B10): {@code webhook-signature} is {@code v1,<base64>} of the HMAC-SHA256
 * of {@code <webhook-id>.<webhook-timestamp>.<body>}, with the bytes of the secret after {@code whsec_}; one per secret
 * that signs, separated by a space.
 */
final class Signature {

    static final String PREFIX = "whsec_";

    private static final SecureRandom RANDOM = new SecureRandom();

    private Signature() {
    }

    /** A new secret: 32 random bytes. */
    static String newSecret() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return PREFIX + Base64.getEncoder().encodeToString(key);
    }

    static String header(List<String> secrets, String id, long timestamp, String body) {
        return secrets.stream().map(s -> "v1," + sign(s, id, timestamp, body)).collect(Collectors.joining(" "));
    }

    static String sign(String secret, String id, long timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.substring(PREFIX.length())), "HmacSHA256"));
            byte[] signed = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signed);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }
}
