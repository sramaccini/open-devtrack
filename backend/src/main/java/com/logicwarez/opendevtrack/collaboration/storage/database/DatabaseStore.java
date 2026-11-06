/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration.storage.database;

import com.logicwarez.opendevtrack.collaboration.storage.AttachmentStore;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The store {@code database}, the default (ADR-14): the bytes in {@code odt_collaboration.attachment_content}, in the
 * transaction of the request that writes them. Nothing to install, and the backups of the database cover them.
 */
@Component
@ConditionalOnProperty(name = "odt.attachments.store", havingValue = DatabaseStore.NAME, matchIfMissing = true)
class DatabaseStore implements AttachmentStore {

    static final String NAME = "database";
    private static final String PROBE = "check/probe";

    private final JdbcTemplate jdbc;

    DatabaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void put(String key, InputStream content, long size) {
        jdbc.update("INSERT INTO odt_collaboration.attachment_content (key, content) VALUES (?, ?) "
                + "ON CONFLICT (key) DO UPDATE SET content = EXCLUDED.content, created_at = now()",
                ps -> {
                    ps.setString(1, key);
                    ps.setBinaryStream(2, content, size);
                });
    }

    @Override
    public InputStream get(String key) throws IOException {
        List<byte[]> found = jdbc.query("SELECT content FROM odt_collaboration.attachment_content WHERE key = ?",
                (rs, n) -> rs.getBytes(1), key);
        if (found.isEmpty()) {
            throw new NoSuchFileException(key);
        }
        return new ByteArrayInputStream(found.getFirst());
    }

    @Override
    public void delete(String key) {
        jdbc.update("DELETE FROM odt_collaboration.attachment_content WHERE key = ?", key);
    }

    @Override
    public void keys(Instant before, Consumer<String> key) {
        jdbc.query("SELECT key FROM odt_collaboration.attachment_content WHERE created_at < ?",
                rs -> {
                    key.accept(rs.getString(1));
                }, Timestamp.from(before));
    }

    @Override
    public void check() throws IOException {
        byte[] probe = "odt".getBytes(StandardCharsets.US_ASCII);
        put(PROBE, new ByteArrayInputStream(probe), probe.length);
        try (InputStream back = get(PROBE)) {
            if (!Arrays.equals(back.readAllBytes(), probe)) {
                throw new IOException("The store database gave back other bytes than those written");
            }
        } finally {
            delete(PROBE);
        }
    }
}
