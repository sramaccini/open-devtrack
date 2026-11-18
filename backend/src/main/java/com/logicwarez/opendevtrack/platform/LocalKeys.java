/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

/**
 * The key in the memory of the back end (ADR-23): AES-GCM with a 256-bit key derived from {@code ODT_SECRET_KEY}
 * (Spring Security Crypto), a random nonce for each value, kept as {@code enc:<key id>:<hex>}. The key is rotated with
 * a new id; the old ones stay in {@code odt.secrets.previous-keys} until every value is written again.
 */
final class LocalKeys implements SecretKeys {

    static final String PREFIX = "enc:";
    private static final Pattern ID = Pattern.compile("[a-z0-9]{1,20}");
    static final int MIN_KEY = 32;

    /** Null without a current key: the plugin then only opens what it wrote before (a move to transit). */
    private final String currentId;
    private final Map<String, TextEncryptor> keys = new LinkedHashMap<>();

    LocalKeys(String key, String keyId, String previous) {
        if (key == null || key.isEmpty()) {
            this.currentId = null;
        } else if (key.length() < MIN_KEY) {
            throw new IllegalStateException("ODT_SECRET_KEY is shorter than " + MIN_KEY + " characters");
        } else {
            this.currentId = checkedId(keyId);
            keys.put(currentId, encryptor(currentId, key));
        }
        for (String entry : previous == null ? new String[0] : previous.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            int equals = entry.indexOf('=');
            if (equals < 0) {
                throw new IllegalStateException("odt.secrets.previous-keys holds id=key pairs separated by commas");
            }
            String id = checkedId(entry.substring(0, equals).strip());
            keys.putIfAbsent(id, encryptor(id, entry.substring(equals + 1).strip()));
        }
    }

    boolean hasKey() {
        return currentId != null;
    }

    @Override
    public String prefix() {
        return PREFIX;
    }

    @Override
    public String seal(String plain) {
        if (currentId == null) {
            throw new IllegalStateException("ODT_SECRET_KEY is missing: the secrets at rest are encrypted with it");
        }
        return PREFIX + currentId + ":" + keys.get(currentId).encrypt(plain);
    }

    @Override
    public String open(String stored) {
        int colon = stored.indexOf(':', PREFIX.length());
        String id = colon < 0 ? "" : stored.substring(PREFIX.length(), colon);
        TextEncryptor encryptor = keys.get(id);
        if (encryptor == null) {
            throw new IllegalStateException("A secret is encrypted under the key " + id
                    + ", which is not configured: add it to odt.secrets.previous-keys");
        }
        return encryptor.decrypt(stored.substring(colon + 1));
    }

    @Override
    public boolean current(String stored) {
        return currentId != null && stored.startsWith(PREFIX + currentId + ":");
    }

    private static String checkedId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalStateException("The id of a secret key is 1 to 20 lower-case letters or digits");
        }
        return id;
    }

    /** The salt of the key derivation is fixed per key id: the key itself carries the entropy. */
    private static TextEncryptor encryptor(String id, String key) {
        if (key.length() < MIN_KEY) {
            throw new IllegalStateException("The secret key " + id + " is shorter than " + MIN_KEY + " characters");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("opendevtrack-secret-key:" + id).getBytes(StandardCharsets.UTF_8));
            return Encryptors.delux(key, HexFormat.of().formatHex(digest, 0, 16));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
