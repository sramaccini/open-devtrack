/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.notification;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The email channel of ADR-18: on only when {@code spring.mail.host} is set; the sender, the address of the application
 * for the links, and the time of the daily digest in the zone of the instance.
 */
@Component
class MailSettings {

    private final boolean enabled;
    private final String from;
    private final String publicUrl;
    private final LocalTime digestAt;
    private final ZoneId zone;

    MailSettings(@Value("${spring.mail.host:}") String host,
                 @Value("${odt.mail.from:OpenDevTrack <noreply@localhost>}") String from,
                 @Value("${odt.public-url:http://localhost:8090}") String publicUrl,
                 @Value("${odt.mail.digest-at:07:00}") LocalTime digestAt,
                 @Value("${odt.reporting.zone:UTC}") String zone) {
        this.enabled = !host.isBlank();
        this.from = from;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.digestAt = digestAt;
        this.zone = ZoneId.of(zone);
    }

    boolean enabled() {
        return enabled;
    }

    String from() {
        return from;
    }

    /** When a message mailed in this mode is due: now, or at the next digest. */
    Instant due(String mode, Instant now) {
        if (Preferences.INSTANT.equals(mode)) {
            return now;
        }
        ZonedDateTime today = now.atZone(zone).toLocalDate().atTime(digestAt).atZone(zone);
        return (today.toInstant().isAfter(now) ? today : today.plusDays(1)).toInstant();
    }

    /** The page of an issue in the application. */
    String issueUrl(String projectKey, String issueKey) {
        return publicUrl + "/projects/issue-detail?key=" + projectKey + "&issue=" + issueKey;
    }

    /** The screen of the notification settings. */
    String settingsUrl() {
        return publicUrl + "/home/notification-settings";
    }
}
