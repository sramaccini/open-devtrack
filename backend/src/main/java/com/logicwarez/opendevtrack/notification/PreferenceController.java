/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import com.logicwarez.opendevtrack.notification.Preferences.Preference;
import com.logicwarez.opendevtrack.platform.ApiDoc;
import com.logicwarez.opendevtrack.platform.CurrentUser;
import com.logicwarez.opendevtrack.platform.Problems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The notification settings of the user (ADR-18), for a form of Quadrava whose rows come from {@code fieldsFrom}: one
 * switch (in the application) and one choice (email) per kind, their values in {@code channels}, as
 * {@code mentionedApp} and {@code mentionedEmail}. The language of the request is kept for the mails of the user.
 */
@RestController
@Transactional(readOnly = true)
@PreAuthorize("hasAuthority('odt.project.browse')")
class PreferenceController {

    static final Set<String> EMAIL = Set.of(Preferences.OFF, Preferences.INSTANT, Preferences.DAILY);

    private final Preferences preferences;
    private final MessageTexts texts;
    private final MailSettings mail;
    private final CurrentUser user;

    PreferenceController(Preferences preferences, MessageTexts texts, MailSettings mail, CurrentUser user) {
        this.preferences = preferences;
        this.texts = texts;
        this.mail = mail;
        this.user = user;
    }

    record PreferencesRequest(Map<String, Object> channels) {
    }

    /** The address of the mails (from the identity provider, read only), whether mail is on, the channels. */
    @ApiDoc(value = "The notification settings of the user", answer = Map.class)
    @GetMapping("/api/notifications/preferences")
    Map<String, Object> get() {
        Map<String, Object> channels = new LinkedHashMap<>();
        preferences.of(user.tenantId(), user.username()).values().forEach(p -> {
            channels.put(p.kind().key() + "App", p.inApp());
            channels.put(p.kind().key() + "Email", p.email());
        });
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("email", user.session().email());
        view.put("mailEnabled", mail.enabled());
        view.put("channels", channels);
        return view;
    }

    /** The rows of the form, in the language of the request. */
    @ApiDoc(value = "The notification settings, as the fields of a form",
            answer = Map.class, list = true, internal = true)
    @GetMapping("/api/notifications/preferences/fields")
    List<Map<String, Object>> fields(Locale locale) {
        List<Map<String, Object>> options = new ArrayList<>();
        for (String value : List.of(Preferences.OFF, Preferences.INSTANT, Preferences.DAILY)) {
            options.add(Map.of("value", value, "label", texts.preference("email." + value, locale)));
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (MessageKind kind : MessageKind.SETTABLE) {
            String label = texts.preference(kind.value(), locale);
            fields.add(Map.of("key", kind.key() + "App", "label", texts.preference("app", locale, label), "type", "switch"));
            fields.add(Map.of("key", kind.key() + "Email", "label", texts.preference("email", locale, label),
                    "type", "select", "options", options, "required", true));
        }
        return fields;
    }

    /** Saves the channels given (the others stay as they are) and the language of the request. */
    @ApiDoc(value = "Changes the notification settings of the user", answer = Map.class)
    @PutMapping("/api/notifications/preferences")
    @Transactional
    ResponseEntity<?> save(@RequestBody PreferencesRequest request, Locale locale) {
        Map<String, Object> channels = request.channels() == null ? Map.of() : request.channels();
        Map<MessageKind, Preference> current = preferences.of(user.tenantId(), user.username());
        List<Preference> changed = new ArrayList<>();
        for (MessageKind kind : MessageKind.SETTABLE) {
            Object app = channels.get(kind.key() + "App");
            Object email = channels.get(kind.key() + "Email");
            if (app != null && !(app instanceof Boolean)) {
                return Problems.badRequest("In the application is true or false");
            }
            if (email != null && !EMAIL.contains(email.toString())) {
                return Problems.badRequest("Email is off, instant or daily");
            }
            Preference was = current.get(kind);
            changed.add(new Preference(kind, app == null ? was.inApp() : (Boolean) app,
                    email == null ? was.email() : email.toString()));
        }
        preferences.save(user.tenantId(), user.username(), changed, locale);
        return ResponseEntity.ok(get());
    }
}
