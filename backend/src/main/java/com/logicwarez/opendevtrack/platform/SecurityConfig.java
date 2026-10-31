/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import com.logicwarez.authz.bff.BffConfigurer;
import com.logicwarez.authz.bff.BffControllers;
import com.logicwarez.authz.rbac.RbacAdminSecurity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * One filter chain for the whole monolith: the BFF of authz-library (login, session, CSRF, Origin check), its RBAC
 * administration API, and every other path signed in.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.with(BffConfigurer.bff(), Customizer.withDefaults())
            .authorizeHttpRequests(auth -> RbacAdminSecurity.adminApi(auth
                    .requestMatchers(BffControllers.PUBLIC_PATHS).permitAll()
                    .requestMatchers("/actuator/health/**", "/actuator/info").permitAll())
                .anyRequest().authenticated());
        return http.build();
    }
}
