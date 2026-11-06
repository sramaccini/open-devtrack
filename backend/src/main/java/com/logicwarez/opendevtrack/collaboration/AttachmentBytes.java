/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration;

import com.logicwarez.opendevtrack.collaboration.storage.AttachmentStore;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The bytes of the attachments in the chosen store, kept consistent with their metadata (ADR-14):
 * <ul>
 * <li>an upload writes the bytes after the metadata are flushed and removes them again if the transaction rolls back;</li>
 * <li>a removal takes the bytes away only after the commit that removed the metadata;</li>
 * <li>a daily sweep removes the bytes older than a day that no metadata name, left by a failure;</li>
 * <li>at start the store must write and read, and hold every attachment already saved.</li>
 * </ul>
 * With the store {@code database} the bytes are in the same transaction as the metadata.
 */
@Component
class AttachmentBytes implements InitializingBean {

    private static final Logger LOG = LoggerFactory.getLogger(AttachmentBytes.class);
    static final Duration SWEEP_AFTER = Duration.ofDays(1);

    private final AttachmentStore store;
    private final AttachmentRepository attachments;

    AttachmentBytes(AttachmentStore store, AttachmentRepository attachments) {
        this.store = store;
        this.attachments = attachments;
    }

    String store() {
        return store.name();
    }

    @Override
    public void afterPropertiesSet() throws IOException {
        store.check();
        if (attachments.existsByStoreNot(store.name())) {
            throw new IllegalStateException("Attachments are kept in a store other than odt.attachments.store="
                    + store.name() + ": changing the store of an installation is a migration (ADR-14)");
        }
        LOG.info("Attachments kept in the store {}", store.name());
    }

    /** Writes the bytes of a flushed attachment, inside its transaction, and records their SHA-256. */
    void keep(AttachmentEntity attachment, InputStream content) throws IOException {
        String key = attachment.key();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    remove(key);
                }
            }
        });
        MessageDigest sha256 = sha256();
        try (InputStream in = new DigestInputStream(content, sha256)) {
            store.put(key, in, attachment.size());
        }
        attachment.received(HexFormat.of().formatHex(sha256.digest()));
    }

    InputStream read(AttachmentEntity attachment) throws IOException {
        return store.get(attachment.key());
    }

    /** Takes the bytes away once the transaction that removed their metadata has committed. */
    void removeAfterCommit(AttachmentEntity attachment) {
        String key = attachment.key();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                remove(key);
            }
        });
    }

    private void remove(String key) {
        try {
            store.delete(key);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Bytes {} not removed from the store {}; the sweep removes them later", key, store.name(), e);
        }
    }

    @Scheduled(cron = "${odt.attachments.sweep-cron:0 45 3 * * *}")
    void sweep() throws IOException {
        sweep(Instant.now().minus(SWEEP_AFTER));
    }

    /** Removes the bytes kept before {@code before} that no attachment names. */
    void sweep(Instant before) throws IOException {
        List<String> keys = new ArrayList<>();
        store.keys(before, keys::add);
        int removed = 0;
        for (String key : keys) {
            if (attachment(key).filter(attachments::existsById).isEmpty()) {
                remove(key);
                removed++;
            }
        }
        if (removed > 0) {
            LOG.info("Removed {} bytes without an attachment from the store {}", removed, store.name());
        }
    }

    /** The attachment named by a key {@code <project id>/<attachment id>}. */
    private static Optional<UUID> attachment(String key) {
        int slash = key.indexOf('/');
        try {
            return slash < 0 ? Optional.empty() : Optional.of(UUID.fromString(key.substring(slash + 1)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
