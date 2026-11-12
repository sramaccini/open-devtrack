/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Where a webhook may post (ADR-21, B12): only public addresses over https, unless private ones are allowed. */
class AddressesTest {

    private final Addresses strict = new Addresses(false);

    @Test
    void privateAndSpecialAddressesAreRefused() {
        for (String url : new String[] {"https://127.0.0.1/hook", "https://localhost/hook", "https://10.1.2.3/hook",
                "https://172.16.0.1/hook", "https://192.168.1.10/hook", "https://169.254.169.254/latest/meta-data",
                "https://100.64.0.1/hook", "https://0.0.0.0/hook", "https://[::1]/hook", "https://[fd00::1]/hook",
                "https://[fe80::1]/hook", "https://[::ffff:10.0.0.1]/hook", "https://240.0.0.1/hook"}) {
            assertThat(strict.refuse(url)).as(url).contains("address");
        }
    }

    @Test
    void onlyAbsoluteHttpsWithoutCredentials() {
        for (String url : new String[] {"http://8.8.8.8/hook", "ftp://8.8.8.8/hook", "https://user:pw@8.8.8.8/hook",
                "/hook", "https://8.8.8.8/hook#part", "not a url", "https://8.8.8.8/" + "x".repeat(2000)}) {
            assertThat(strict.refuse(url)).as(url).contains("url");
        }
        assertThat(strict.refuse("https://8.8.8.8/hook?project=WEB")).isEmpty();
        assertThat(strict.refuse("https://[2001:4860:4860::8888]/hook")).isEmpty();
    }

    @Test
    void privateAddressesAndHttpWhenAllowed() {
        Addresses development = new Addresses(true);
        assertThat(development.refuse("http://127.0.0.1:8080/hook")).isEmpty();
        assertThat(development.refuse("http://echo:8080/hook")).isEmpty();
        assertThat(development.refuse("ftp://127.0.0.1/hook")).isEqualTo(Optional.of("url"));
    }
}
