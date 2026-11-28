/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.issue;

import com.logicwarez.opendevtrack.issue.LinkTypeDefaults.Default;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * The link types of a tenant (ADR-29) and how the API names and shows each end of a link. A relation is the code of
 * a type read from its {@code from} end and {@code code:inward} read from the other; the system types keep the
 * relations of before ({@code blocks}, {@code blocked_by}, {@code relates}…), so the API stays as it was for them. A
 * system type whose descriptions were not changed is shown in the language of the user; any other with its
 * descriptions as written.
 */
@Component
class LinkTypes {

    static final String INWARD = ":inward";

    private static final Map<String, Default> DEFAULTS = LinkTypeDefaults.ALL.stream()
            .collect(Collectors.toUnmodifiableMap(Default::code, Function.identity()));

    /** A relation of the API: the type, and whether the issue that names it is at the {@code from} end. */
    record Relation(LinkTypeEntity type, boolean outward) {
    }

    /** One end of a type, for the choice of a relation: its name in the API and its description. */
    record Choice(String relation, String label, String type) {
    }

    private final LinkTypeRepository types;
    private final LinkTypeDefaults defaults;
    private final ResourceBundleMessageSource texts = new ResourceBundleMessageSource();

    LinkTypes(LinkTypeRepository types, LinkTypeDefaults defaults) {
        this.types = types;
        this.defaults = defaults;
        texts.setBasename("issue/export");
        texts.setDefaultEncoding("UTF-8");
        texts.setFallbackToSystemLocale(false);
    }

    /** The types of the tenant, made the first time: the system ones first, then by code. */
    List<LinkTypeEntity> of(String tenantId) {
        if (!types.existsByTenantId(tenantId)) {
            try {
                defaults.make(tenantId);
            } catch (DataIntegrityViolationException meanwhile) {
                // Another request made them: they are read below.
            }
        }
        return types.findByTenantIdOrderBySystemDescCodeAsc(tenantId);
    }

    Optional<LinkTypeEntity> find(String tenantId, String code) {
        return of(tenantId).stream().filter(t -> t.code().equals(code)).findFirst();
    }

    /** The relation of the API named so, among the types of the tenant. */
    Optional<Relation> parse(String tenantId, String relation) {
        if (relation == null) {
            return Optional.empty();
        }
        for (LinkTypeEntity type : of(tenantId)) {
            if (relation(type, true).equals(relation)) {
                return Optional.of(new Relation(type, true));
            }
            if (relation(type, false).equals(relation)) {
                return Optional.of(new Relation(type, false));
            }
        }
        return Optional.empty();
    }

    /** The relations of the tenant, as the issue that names them reads them: a symmetric type once. */
    List<Choice> choices(String tenantId) {
        List<Choice> all = new ArrayList<>();
        for (LinkTypeEntity type : of(tenantId)) {
            all.add(new Choice(relation(type, true), label(type, true), type.code()));
            if (!type.symmetric()) {
                all.add(new Choice(relation(type, false), label(type, false), type.code()));
            }
        }
        return all;
    }

    /** The name of the relation in the API, read from the {@code from} end or from the other. */
    static String relation(LinkTypeEntity type, boolean outward) {
        Default system = type.system() ? DEFAULTS.get(type.code()) : null;
        if (system != null) {
            return outward ? system.outwardRelation() : system.inwardRelation();
        }
        return outward || type.symmetric() ? type.code() : type.code() + INWARD;
    }

    /** What the end reads: in the language of the user for a system type as it was made, else as written. */
    String label(LinkTypeEntity type, boolean outward) {
        String written = outward ? type.outward() : type.inward();
        Default system = type.system() ? DEFAULTS.get(type.code()) : null;
        if (system == null || !system.outward().equals(type.outward()) || !system.inward().equals(type.inward())) {
            return written;
        }
        return texts.getMessage("relation." + relation(type, outward), null, written, LocaleContextHolder.getLocale());
    }
}
