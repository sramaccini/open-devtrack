/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Types of link between two issues, each read from both ends: ODT-1 {@code blocks} ODT-2, so ODT-2 is
 * {@code blocked_by} ODT-1. {@code relates} reads the same from both.
 */
enum LinkType {

    BLOCKS("blocks", "blocked_by"),
    RELATES("relates", "relates"),
    DUPLICATES("duplicates", "duplicated_by");

    private final String outward;
    private final String inward;

    LinkType(String outward, String inward) {
        this.outward = outward;
        this.inward = inward;
    }

    /** The relation as the issue at the {@code from} end ({@code outward}) or at the {@code to} end reads it. */
    String relation(boolean outward) {
        return outward ? this.outward : inward;
    }

    /** A relation of the API, with the end the issue that names it is at. */
    record Relation(LinkType type, boolean outward) {
    }

    static Optional<Relation> parse(String relation) {
        for (LinkType type : values()) {
            if (type.outward.equals(relation)) {
                return Optional.of(new Relation(type, true));
            }
            if (type.inward.equals(relation)) {
                return Optional.of(new Relation(type, false));
            }
        }
        return Optional.empty();
    }

    static List<String> relations() {
        return Arrays.stream(values()).flatMap(t -> Stream.of(t.outward, t.inward)).distinct().toList();
    }
}
