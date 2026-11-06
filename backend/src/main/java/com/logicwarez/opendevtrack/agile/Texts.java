/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.agile;

import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.stereotype.Component;

/**
 * The headers that the boards and the backlog give to the kanban of Quadrava, shown as they are: English, or Italian
 * when the request asks for it ({@code agile/texts*.properties}).
 */
@Component
class Texts {

    private final ResourceBundleMessageSource texts = new ResourceBundleMessageSource();

    Texts() {
        texts.setBasename("agile/texts");
        texts.setDefaultEncoding("UTF-8");
        texts.setFallbackToSystemLocale(false);
    }

    /** The label of a status of the built-in workflow, or its code. */
    String status(String code) {
        try {
            return texts.getMessage("status." + code, null, LocaleContextHolder.getLocale());
        } catch (NoSuchMessageException e) {
            return code;
        }
    }

    String backlog() {
        return texts.getMessage("backlog", null, LocaleContextHolder.getLocale());
    }
}
