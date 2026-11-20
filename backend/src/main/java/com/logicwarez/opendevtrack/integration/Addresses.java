/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Where a webhook may post (ADR-21, B12): an absolute {@code https} URL without user or password, whose host resolves
 * only to public addresses. Loopback, private, link-local (the metadata of the clouds), shared, multicast and reserved
 * ranges are refused, when the webhook is saved and again before each delivery, since the DNS can change.
 * {@code odt.webhooks.allow-private} (development and tests) allows them, and {@code http}.
 * {@code odt.webhooks.allowed-hosts} (ADR-25), a list of host names separated by commas, narrows that further when it
 * is not empty: only those hosts, compared without case, and still only at public addresses. The public demo allows
 * only its own echo, so that its visitors cannot use it to send requests to other hosts.
 */
@Component
class Addresses {

    static final int MAX_URL = 2000;

    private final boolean allowPrivate;
    private final Set<String> allowedHosts;

    Addresses(@Value("${odt.webhooks.allow-private:false}") boolean allowPrivate,
            @Value("${odt.webhooks.allowed-hosts:}") String allowedHosts) {
        this.allowPrivate = allowPrivate;
        this.allowedHosts = Arrays.stream(allowedHosts.split(","))
                .map(host -> host.strip().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Why the URL is refused, as the {@code code} of the field: {@code url} or {@code address}; empty if allowed. */
    Optional<String> refuse(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return Optional.of("url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeAllowed = scheme.equals("https") || allowPrivate && scheme.equals("http");
        if (url.length() > MAX_URL || !schemeAllowed || uri.getHost() == null || uri.getRawUserInfo() != null
                || uri.getRawFragment() != null) {
            return Optional.of("url");
        }
        if (!allowedHosts.isEmpty() && !allowedHosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            return Optional.of("address");
        }
        if (allowPrivate) {
            return Optional.empty();
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (!isPublic(address)) {
                    return Optional.of("address");
                }
            }
        } catch (UnknownHostException e) {
            return Optional.of("address");
        }
        return Optional.empty();
    }

    static boolean isPublic(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first != 0                                      // this network
                    && !(first == 100 && second >= 64 && second < 128) // shared, carrier-grade NAT
                    && !(first == 192 && second == 0 && (b[2] & 0xff) == 0) // IETF protocol assignments
                    && !(first == 198 && (second == 18 || second == 19))   // benchmarking
                    && first < 240;                                // reserved and broadcast
        }
        if (address instanceof Inet6Address) {
            int first = b[0] & 0xff;
            boolean uniqueLocal = (first & 0xfe) == 0xfc;           // fc00::/7
            boolean mappedV4 = isMappedV4(b);
            if (mappedV4) {
                try {
                    return isPublic(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
            return !uniqueLocal;
        }
        return false;
    }

    private static boolean isMappedV4(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }
}
