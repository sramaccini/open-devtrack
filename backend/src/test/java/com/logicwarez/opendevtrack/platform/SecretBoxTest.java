/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The secrets at rest (ADR-23): AES-GCM under the key of the installation, with its id, and its rotation. */
class SecretBoxTest {

    private static final String OLD = "an-old-key-of-at-least-thirty-two-characters";
    private static final String NEW = "a-new-key-of-at-least-thirty-two-characters!";

    @Test
    void sealsAndOpens() {
        SecretBox box = new SecretBox(OLD, "1", "");
        String sealed = box.seal("whsec_abc");
        assertThat(sealed).startsWith("enc:1:").doesNotContain("abc");
        assertThat(box.seal("whsec_abc")).as("a new nonce each time").isNotEqualTo(sealed);
        assertThat(box.open(sealed)).isEqualTo("whsec_abc");
        assertThat(box.open("whsec_clear")).as("stored before the encryption").isEqualTo("whsec_clear");
        assertThat(box.stale("whsec_clear")).isTrue();
        assertThat(box.stale(sealed)).isFalse();
        assertThat(box.seal(null)).isNull();
        assertThat(box.open(null)).isNull();
    }

    @Test
    void rotatesTheKey() {
        String sealed = new SecretBox(OLD, "1", "").seal("whsec_abc");
        SecretBox rotated = new SecretBox(NEW, "2", "1=" + OLD);
        assertThat(rotated.open(sealed)).isEqualTo("whsec_abc");
        assertThat(rotated.stale(sealed)).isTrue();
        String again = rotated.reseal(sealed).orElseThrow();
        assertThat(again).startsWith("enc:2:");
        assertThat(rotated.reseal(again)).isEmpty();
        assertThatThrownBy(() -> new SecretBox(NEW, "2", "").open(sealed))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("previous-keys");
        assertThatThrownBy(() -> new SecretBox(OLD, "2", "").open(again))
                .as("the wrong key under the same id").isInstanceOf(RuntimeException.class);
    }

    @Test
    void refusesToStartWithoutAKey() {
        assertThatThrownBy(() -> new SecretBox("", "1", "")).hasMessageContaining("ODT_SECRET_KEY");
        assertThatThrownBy(() -> new SecretBox("short", "1", "")).hasMessageContaining("ODT_SECRET_KEY");
        assertThatThrownBy(() -> new SecretBox(OLD, "Key 1", "")).hasMessageContaining("id");
        assertThatThrownBy(() -> new SecretBox(OLD, "1", "2" + OLD)).hasMessageContaining("id=key");
    }
}
