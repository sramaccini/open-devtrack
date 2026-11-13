/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import com.logicwarez.authz.session.SessionAuthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One log line for each refusal of access control (ASVS 7.1.3, 7.2.2), whoever refuses: the BFF of authz-library
 * (no session, CSRF, Origin), the permissions of a method, or a controller. 401 and 403 at WARN; 404 of the API at
 * INFO, since it is also what a user gets for a resource of another tenant (ADR-09), and the answer must not tell
 * the two apart. It runs inside the security chain, right after the session is loaded, so it knows the user and
 * the tenant; the path is logged without its query, which may hold search texts.
 */
class AccessRefusalLog extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessRefusalLog.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            int status = response.getStatus();
            boolean refused = status == HttpServletResponse.SC_UNAUTHORIZED || status == HttpServletResponse.SC_FORBIDDEN;
            boolean notFound = status == HttpServletResponse.SC_NOT_FOUND && request.getRequestURI().startsWith("/api/");
            if (refused || notFound) {
                Authentication user = SecurityContextHolder.getContext().getAuthentication();
                String tenant = user instanceof SessionAuthentication session ? session.getPrincipal().tenantId() : null;
                String name = user == null || !user.isAuthenticated() ? null : user.getName();
                LoggingEventBuilder line = refused ? log.atWarn() : log.atInfo();
                line.addKeyValue("http.response.status_code", status)
                        .addKeyValue("http.request.method", request.getMethod())
                        .addKeyValue("url.path", request.getRequestURI())
                        .addKeyValue("user.name", name)
                        .addKeyValue("odt.tenant", tenant)
                        .log("{} {} {}: user {}, tenant {}", refused ? "Access refused" : "Not found",
                                request.getMethod(), request.getRequestURI(), name == null ? "-" : name,
                                tenant == null ? "-" : tenant);
            }
        }
    }
}
