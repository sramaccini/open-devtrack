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
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Component;

/**
 * Secrets at rest (ADR-23): AES-GCM with a 256-bit key derived from the key of the installation ({@code ODT_SECRET_KEY},
 * Spring Security Crypto), a random nonce for each value. A value is kept as {@code enc:<key id>:<hex>}, so the key can
 * be rotated: the new key gets a new id, the old one stays in {@code odt.secrets.previous-keys} until every value is
 * written again under the new one (at start, by the modules that keep secrets). The back end does not start without
 * the key.
 */
@Component
public class SecretBox {

    static final String PREFIX = "enc:";
    private static final Pattern ID = Pattern.compile("[a-z0-9]{1,20}");
    private static final int MIN_KEY = 32;

    private final String currentId;
    private final Map<String, TextEncryptor> keys = new LinkedHashMap<>();

    SecretBox(@Value("${odt.secrets.key:}") String key, @Value("${odt.secrets.key-id:1}") String keyId,
              @Value("${odt.secrets.previous-keys:}") String previous) {
        if (key == null || key.length() < MIN_KEY) {
            throw new IllegalStateException("ODT_SECRET_KEY is missing or shorter than " + MIN_KEY
                    + " characters: the secrets at rest are encrypted with it (ADR-23)");
        }
        this.currentId = checkedId(keyId);
        keys.put(currentId, encryptor(currentId, key));
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

    /** The value encrypted under the current key. */
    public String seal(String plain) {
        return plain == null ? null : PREFIX + currentId + ":" + keys.get(currentId).encrypt(plain);
    }

    /**
     * The value in clear. A value without the prefix was stored before the encryption and is given back as it is; one
     * under an unknown key is an error of the installation.
     */
    public String open(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored;
        }
        int colon = stored.indexOf(':', PREFIX.length());
        String id = colon < 0 ? "" : stored.substring(PREFIX.length(), colon);
        TextEncryptor encryptor = keys.get(id);
        if (encryptor == null) {
            throw new IllegalStateException("A secret is encrypted under the key " + id
                    + ", which is not configured: add it to odt.secrets.previous-keys");
        }
        return encryptor.decrypt(stored.substring(colon + 1));
    }

    /** Whether a stored value must be written again: in clear, or under a key that is not the current one. */
    public boolean stale(String stored) {
        return stored != null && !stored.startsWith(PREFIX + currentId + ":");
    }

    /** The same value under the current key, or empty when it already is. */
    public Optional<String> reseal(String stored) {
        return stale(stored) ? Optional.of(seal(open(stored))) : Optional.empty();
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
