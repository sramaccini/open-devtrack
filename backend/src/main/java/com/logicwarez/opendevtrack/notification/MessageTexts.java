/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import java.util.Locale;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * The title and text of a message in the language of the reader (ADR-13): English, or Italian when the request asks
 * for it ({@code notification/messages*.properties}).
 */
@Component
class MessageTexts {

    private final ResourceBundleMessageSource texts = new ResourceBundleMessageSource();

    MessageTexts() {
        texts.setBasename("notification/messages");
        texts.setDefaultEncoding("UTF-8");
        texts.setFallbackToSystemLocale(false);
    }

    record Text(String title, String text) {
    }

    Text of(MessageEntity m, Locale locale) {
        String to = status(m.params().get("to"), locale);
        String title = texts.getMessage("message." + m.kind().value() + ".title", new Object[] {m.actor(), m.issueKey(), to},
                locale);
        String text = switch (m.kind()) {
            case MENTIONED, COMMENTED -> m.params().get("excerpt");
            case TRANSITIONED -> texts.getMessage("message.transitioned.text",
                    new Object[] {status(m.params().get("from"), locale), to}, locale);
            case ASSIGNED -> null;
        };
        return new Text(title, text);
    }

    /**
     * A text of the mails ({@code mail.*}): {0} the issue key, {1} its summary, {2} a link, {3} a number. Without
     * arguments the text is taken as written (no MessageFormat, so an apostrophe stays).
     */
    String mail(String key, Locale locale, Object... args) {
        return texts.getMessage("mail." + key, args.length == 0 ? null : args, locale);
    }

    /** A label of the notification settings ({@code preference.*}). */
    String preference(String key, Locale locale, Object... args) {
        return texts.getMessage("preference." + key, args.length == 0 ? null : args, locale);
    }

    /** The label of a status of the built-in workflow, or its code. */
    private String status(String code, Locale locale) {
        if (code == null) {
            return "";
        }
        try {
            return texts.getMessage("status." + code, null, locale);
        } catch (NoSuchMessageException e) {
            return code;
        }
    }
}
