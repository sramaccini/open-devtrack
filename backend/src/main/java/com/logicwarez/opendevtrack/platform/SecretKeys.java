/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

/**
 * Where the key of the secrets at rest lives (ADR-24 point 5): {@code local}, in the memory of the back end
 * ({@code ODT_SECRET_KEY}), or {@code transit}, in the transit engine of Vault or OpenBao, which encrypts and decrypts
 * without the key ever leaving it. Each plugin writes its values with a prefix of its own, so {@link SecretBox} opens
 * a value written by either, and writes again under the chosen one what another wrote.
 */
interface SecretKeys {

    /** The start of every value this plugin writes, {@code enc:} or {@code transit:}. */
    String prefix();

    /** The value encrypted under the current key, with the prefix. */
    String seal(String plain);

    /** The value in clear; the stored value starts with the prefix. */
    String open(String stored);

    /** Whether a value of this plugin is under its current key. */
    boolean current(String stored);
}
