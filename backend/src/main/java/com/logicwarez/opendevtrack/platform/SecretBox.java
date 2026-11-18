/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Secrets at rest (ADR-23, ADR-24 point 5). The key is held by a plugin, {@code odt.secrets.plugin}:
 *
 * <ul>
 *   <li>{@code local}, the default: {@link LocalKeys}, the key of the installation ({@code ODT_SECRET_KEY}) in the
 *   memory of the back end, values {@code enc:<key id>:<hex>};</li>
 *   <li>{@code transit}: {@link TransitKeys}, the transit engine of Vault or OpenBao, values {@code transit:…}.</li>
 * </ul>
 *
 * A value written by either is opened as long as its plugin is configured: the local keys stay configured while
 * values under them remain. A value under another key or plugin is stale, and the modules that keep secrets write it
 * again under the chosen one at start; that is how a key is rotated and how an installation moves to transit. The
 * back end does not start without a key for the chosen plugin.
 */
@Component
public class SecretBox {

    private final SecretKeys sealing;
    private final List<SecretKeys> plugins;

    /** Only local keys, as before the plugins. */
    SecretBox(String key, String keyId, String previous) {
        this(required(new LocalKeys(key, keyId, previous)), null);
    }

    @Autowired
    SecretBox(@Value("${odt.secrets.plugin:local}") String plugin,
              @Value("${odt.secrets.key:}") String key, @Value("${odt.secrets.key-id:1}") String keyId,
              @Value("${odt.secrets.previous-keys:}") String previous,
              @Value("${odt.secrets.transit.address:}") String address,
              @Value("${odt.secrets.transit.mount:transit}") String mount,
              @Value("${odt.secrets.transit.key:opendevtrack}") String transitKey,
              @Value("${odt.secrets.transit.token:}") String token,
              @Value("${odt.secrets.transit.token-file:}") String tokenFile) {
        this(switch (plugin) {
            case "local" -> required(new LocalKeys(key, keyId, previous));
            case "transit" -> new LocalKeys(key, keyId, previous);
            default -> throw new IllegalStateException("odt.secrets.plugin is local or transit, not " + plugin);
        }, "transit".equals(plugin) ? new TransitKeys(address, mount, transitKey, token, tokenFile) : null);
    }

    private SecretBox(LocalKeys local, TransitKeys transit) {
        this.sealing = transit != null ? transit : local;
        this.plugins = transit != null ? List.of(transit, local) : List.of(local);
    }

    private static LocalKeys required(LocalKeys local) {
        if (!local.hasKey()) {
            throw new IllegalStateException("ODT_SECRET_KEY is missing or shorter than " + LocalKeys.MIN_KEY
                    + " characters: the secrets at rest are encrypted with it (ADR-23)");
        }
        return local;
    }

    /** The value encrypted under the current key of the chosen plugin. */
    public String seal(String plain) {
        return plain == null ? null : sealing.seal(plain);
    }

    /**
     * The value in clear. A value without a prefix was stored before the encryption and is given back as it is; one
     * under an unknown key is an error of the installation.
     */
    public String open(String stored) {
        if (stored == null) {
            return null;
        }
        return plugins.stream().filter(p -> stored.startsWith(p.prefix())).findFirst()
                .map(p -> p.open(stored))
                .orElse(stored);
    }

    /** Whether a stored value must be written again: in clear, or not under the current key of the chosen plugin. */
    public boolean stale(String stored) {
        return stored != null && !(stored.startsWith(sealing.prefix()) && sealing.current(stored));
    }

    /** The same value under the current key, or empty when it already is. */
    public Optional<String> reseal(String stored) {
        return stale(stored) ? Optional.of(seal(open(stored))) : Optional.empty();
    }
}
