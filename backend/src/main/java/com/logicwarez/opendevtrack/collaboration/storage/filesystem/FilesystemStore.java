/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration.storage.filesystem;

import com.logicwarez.opendevtrack.collaboration.storage.AttachmentStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The store {@code filesystem} (ADR-14): a file per attachment under {@code odt.attachments.filesystem.root}, as
 * {@code <root>/<project id>/<attachment id>}. A file is written under {@code <root>/.tmp} and then moved into place,
 * so a half-written file is never read. For a disk or a shared volume.
 */
@Component
@ConditionalOnProperty(name = "odt.attachments.store", havingValue = FilesystemStore.NAME)
class FilesystemStore implements AttachmentStore {

    static final String NAME = "filesystem";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9-]{1,40}/[A-Za-z0-9-]{1,40}");
    private static final String TMP = ".tmp";
    private static final String PROBE = "check/probe";

    private final Path root;

    FilesystemStore(@Value("${odt.attachments.filesystem.root}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public String name() {
        return NAME;
    }

    /** The file of a key; a key is two plain segments, so it can never leave the root. */
    private Path file(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Not a key of an attachment: " + key);
        }
        return root.resolve(key);
    }

    @Override
    public void put(String key, InputStream content, long size) throws IOException {
        Path target = file(key);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createDirectories(root.resolve(TMP));
        Path part = Files.createTempFile(tmp, "put-", ".part");
        try {
            long written = Files.copy(content, part, StandardCopyOption.REPLACE_EXISTING);
            if (written != size) {
                throw new IOException("Expected " + size + " bytes, received " + written);
            }
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(part);
        }
    }

    @Override
    public InputStream get(String key) throws IOException {
        return Files.newInputStream(file(key));
    }

    @Override
    public void delete(String key) throws IOException {
        Path file = file(key);
        Files.deleteIfExists(file);
        try (Stream<Path> left = Files.list(file.getParent())) {
            if (left.findAny().isEmpty()) {
                Files.deleteIfExists(file.getParent());
            }
        } catch (NoSuchFileException | DirectoryNotEmptyException e) {
            // Already gone, or a file arrived in the meantime: the folder stays.
        }
    }

    /** Also removes the temporary files older than {@code before}, left by an upload that stopped half-way. */
    @Override
    public void keys(Instant before, Consumer<String> key) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> files = Files.walk(root, 2)) {
            files.filter(Files::isRegularFile).filter(f -> older(f, before)).forEach(f -> {
                Path relative = root.relativize(f);
                if (relative.getName(0).toString().equals(TMP)) {
                    delete(f);
                } else if (relative.getNameCount() == 2) {
                    key.accept(relative.getName(0) + "/" + relative.getName(1));
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static boolean older(Path file, Instant before) {
        try {
            return Files.getLastModifiedTime(file).toInstant().isBefore(before);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void check() throws IOException {
        Files.createDirectories(root);
        byte[] probe = "odt".getBytes(StandardCharsets.US_ASCII);
        put(PROBE, new ByteArrayInputStream(probe), probe.length);
        try (InputStream back = get(PROBE)) {
            if (!Arrays.equals(back.readAllBytes(), probe)) {
                throw new IOException("The store filesystem gave back other bytes than those written in " + root);
            }
        } finally {
            delete(PROBE);
        }
    }
}
