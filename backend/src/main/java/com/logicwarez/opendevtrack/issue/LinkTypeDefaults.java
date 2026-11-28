/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The four link types every tenant starts with (ADR-29), made the first time the tenant needs them, in a transaction of
 * their own: the reads of the links are read-only.
 */
@Component
class LinkTypeDefaults {

    /** A system type: its code, its descriptions in English, and the relations of the API for each end. */
    record Default(String code, String outward, String inward, String outwardRelation, String inwardRelation) {
    }

    static final String CLONES = "clones";

    static final List<Default> ALL = List.of(
            new Default("blocks", "blocks", "is blocked by", "blocks", "blocked_by"),
            new Default("relates", "relates to", "relates to", "relates", "relates"),
            new Default("duplicates", "duplicates", "is duplicated by", "duplicates", "duplicated_by"),
            new Default(CLONES, "clones", "is cloned by", "clones", "cloned_by"));

    private final LinkTypeRepository types;

    LinkTypeDefaults(LinkTypeRepository types) {
        this.types = types;
    }

    /**
     * Makes the system types of the tenant; a {@link org.springframework.dao.DataIntegrityViolationException} if
     * another request made them in the meantime, and the caller reads those.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void make(String tenantId) {
        if (types.existsByTenantId(tenantId)) {
            return;
        }
        Instant now = Instant.now();
        types.saveAllAndFlush(ALL.stream()
                .map(d -> new LinkTypeEntity(tenantId, d.code(), d.outward(), d.inward(), true, "system", now))
                .toList());
    }
}
