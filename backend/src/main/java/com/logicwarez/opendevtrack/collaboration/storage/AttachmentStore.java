/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.collaboration.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Where the bytes of the attachments are kept (ADR-14): one plugin, chosen by {@code odt.attachments.store}, among
 * {@code database} (the default), {@code filesystem} and {@code s3}. A key is {@code <project id>/<attachment id>}, two
 * UUIDs: never a name given by a user. The metadata of the attachments stay in PostgreSQL whatever the store.
 */
public interface AttachmentStore {

    /** The value of {@code odt.attachments.store} for this plugin, kept with each attachment. */
    String name();

    /** Keeps {@code size} bytes read from {@code content} under {@code key}; a key already kept is replaced. */
    void put(String key, InputStream content, long size) throws IOException;

    /** The bytes of {@code key}, to be closed by the caller; {@link java.nio.file.NoSuchFileException} if missing. */
    InputStream get(String key) throws IOException;

    /** Removes the bytes of {@code key}; nothing happens if they are not there. */
    void delete(String key) throws IOException;

    /** Every key kept before {@code before}, for the sweep of the bytes left without metadata. */
    void keys(Instant before, Consumer<String> key) throws IOException;

    /** Writes, reads and removes a probe, so that a store that cannot work stops the start of the back end. */
    void check() throws IOException;
}
