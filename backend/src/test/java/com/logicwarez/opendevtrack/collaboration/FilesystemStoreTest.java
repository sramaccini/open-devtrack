/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The attachments on the store filesystem (ADR-14), under a temporary folder. */
class FilesystemStoreTest extends AttachmentTestBase {

    static final Path ROOT = folder();

    private static Path folder() {
        try {
            return Files.createTempDirectory("odt-attachments");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void store(DynamicPropertyRegistry registry) {
        registry.add("odt.attachments.store", () -> "filesystem");
        registry.add("odt.attachments.filesystem.root", ROOT::toString);
    }

    @Override
    String expectedStore() {
        return "filesystem";
    }

    @Test
    void aFileIsNamedByItsKeyNeverByItsName() throws Exception {
        String key = key(upload(luisa, "../../notes.txt", "text/plain", "notes".getBytes(StandardCharsets.UTF_8)));
        Path file = ROOT.resolve(key);
        assertThat(file).hasContent("notes");
        assertThat(ROOT.resolve("notes.txt")).doesNotExist();
        assertThat(ROOT.getParent().resolve("notes.txt")).doesNotExist();
    }
}
