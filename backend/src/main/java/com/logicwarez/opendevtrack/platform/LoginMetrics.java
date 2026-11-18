/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AbstractAuthenticationFailureEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2LoginAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * The logins through the identity provider counted by outcome in {@code odt.logins} (ADR-24), for the alerts of
 * {@code docs/operations/alerts.yaml}. Only the OIDC logins of the BFF: the requests with a token are not logins. A
 * wrong password never reaches the back end: Keycloak refuses it and records it in its own events (installation).
 */
@Component
class LoginMetrics {

    private final MeterRegistry metrics;

    LoginMetrics(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    @EventListener
    void succeeded(AuthenticationSuccessEvent event) {
        count(event.getAuthentication(), "success");
    }

    @EventListener
    void failed(AbstractAuthenticationFailureEvent event) {
        count(event.getAuthentication(), "failure");
    }

    private void count(Authentication authentication, String outcome) {
        if (authentication instanceof OAuth2LoginAuthenticationToken) {
            metrics.counter("odt.logins", "outcome", outcome).increment();
        }
    }
}
