/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import java.util.Collection;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

/**
 * What each user wants for each kind of message (ADR-18), in {@code odt_notification.preference}: the defaults when
 * there is no row, and the language of their mails, the one they last used.
 */
@Component
class Preferences {

    static final String OFF = "off";
    static final String INSTANT = "instant";
    static final String DAILY = "daily";

    private static final Table<?> PREFERENCE = table(name("odt_notification", "preference"));
    private static final Field<String> TENANT_ID = field(name("tenant_id"), SQLDataType.VARCHAR);
    private static final Field<String> USERNAME = field(name("username"), SQLDataType.VARCHAR);
    private static final Field<String> KIND = field(name("kind"), SQLDataType.VARCHAR);
    private static final Field<Boolean> IN_APP = field(name("in_app"), SQLDataType.BOOLEAN);
    private static final Field<String> EMAIL = field(name("email"), SQLDataType.VARCHAR);
    private static final Field<String> LANGUAGE = field(name("language"), SQLDataType.VARCHAR);

    private final DSLContext sql;

    Preferences(DSLContext sql) {
        this.sql = sql;
    }

    /** One kind: in the application or not, and email {@code off}, {@code instant} or {@code daily}. */
    record Preference(MessageKind kind, boolean inApp, String email) {

        /** Mentions, assignments and reviews asked at once, comments and status changes in the digest. */
        static Preference defaults(MessageKind kind) {
            return new Preference(kind, true, kind == MessageKind.MENTIONED || kind == MessageKind.ASSIGNED
                    || kind == MessageKind.REVIEW_REQUESTED ? INSTANT : DAILY);
        }
    }

    /** The preferences of the user for every kind, the defaults where they have none. */
    Map<MessageKind, Preference> of(String tenantId, String username) {
        Map<MessageKind, Preference> all = new EnumMap<>(MessageKind.class);
        for (MessageKind kind : MessageKind.SETTABLE) {
            all.put(kind, Preference.defaults(kind));
        }
        for (Record r : sql.select(KIND, IN_APP, EMAIL).from(PREFERENCE)
                .where(TENANT_ID.eq(tenantId)).and(USERNAME.eq(username)).fetch()) {
            MessageKind kind = MessageKind.parse(r.get(KIND));
            all.put(kind, new Preference(kind, r.get(IN_APP), r.get(EMAIL)));
        }
        return all;
    }

    /** The language of the mails of the user: the one they last used, English when unknown. */
    String language(String tenantId, String username) {
        return sql.select(LANGUAGE).from(PREFERENCE).where(TENANT_ID.eq(tenantId)).and(USERNAME.eq(username))
                .limit(1).fetchOptional(LANGUAGE).orElse("en");
    }

    /** Saves the preferences of the user and the language they use now. */
    void save(String tenantId, String username, Collection<Preference> preferences, Locale locale) {
        Map<MessageKind, Preference> all = of(tenantId, username);
        preferences.forEach(p -> all.put(p.kind(), p));
        write(tenantId, username, all, language(locale));
    }

    /** Remembers the language the user uses now, for their mails; nothing changes when it is the same. */
    void remember(String tenantId, String username, Locale locale) {
        String language = language(locale);
        boolean same = sql.fetchCount(PREFERENCE, TENANT_ID.eq(tenantId).and(USERNAME.eq(username))
                .and(LANGUAGE.ne(language))) == 0
                && sql.fetchCount(PREFERENCE, TENANT_ID.eq(tenantId).and(USERNAME.eq(username))) > 0;
        if (!same) {
            write(tenantId, username, of(tenantId, username), language);
        }
    }

    private void write(String tenantId, String username, Map<MessageKind, Preference> all, String language) {
        for (Preference p : all.values()) {
            sql.insertInto(PREFERENCE)
                    .set(TENANT_ID, tenantId)
                    .set(USERNAME, username)
                    .set(KIND, p.kind().value())
                    .set(IN_APP, p.inApp())
                    .set(EMAIL, p.email())
                    .set(LANGUAGE, language)
                    .onConflict(TENANT_ID, USERNAME, KIND)
                    .doUpdate()
                    .set(IN_APP, p.inApp())
                    .set(EMAIL, p.email())
                    .set(LANGUAGE, language)
                    .execute();
        }
    }

    /** The languages of the catalogues: Italian or English. */
    static String language(Locale locale) {
        return locale != null && locale.getLanguage().equals("it") ? "it" : "en";
    }
}
