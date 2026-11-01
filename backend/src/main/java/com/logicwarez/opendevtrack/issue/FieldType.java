/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Types of the custom fields (ADR-11), named as the simple controls of {@code fieldsFrom} in Quadrava; {@code user}
 * is drawn by the model of the same name, a lookup over the members of the project.
 */
enum FieldType {

    TEXT, NUMBER, DATE, CHECKBOX, SELECT, MULTISELECT, USER;

    /** Name in the API and in the rows of {@code fieldsFrom}: {@code text}, {@code number}… */
    String value() {
        return name().toLowerCase(Locale.ROOT);
    }

    boolean hasOptions() {
        return this == SELECT || this == MULTISELECT;
    }

    static Optional<FieldType> parse(String value) {
        return Arrays.stream(values()).filter(t -> t.value().equals(value)).findFirst();
    }

    static List<String> names() {
        return Arrays.stream(values()).map(FieldType::value).toList();
    }
}
