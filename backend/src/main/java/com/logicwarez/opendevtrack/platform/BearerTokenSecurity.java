/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package com.logicwarez.opendevtrack.platform;

import com.logicwarez.authz.session.AuthorizationResolver;
import com.logicwarez.authz.session.UserNotAuthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Tools that cannot hold a session (the Quadrava CLI publishing {@code ui/}, the conformance kit of the registry) call
 * the registry with a Keycloak access token: {@code Authorization: Bearer} on {@code /registry/**} only (ADR-03).
 *
 * <p>The token proves who the user is; the permissions are the same as in a session, resolved by authz-library for that
 * user ({@link AuthorizationResolver}, the tenant the user last worked on). Only tokens issued to the clients listed in
 * {@code odt.security.bearer.clients} ({@code azp}) are accepted. Temporary until authz-library offers bearer access
 * itself (request A4).
 */
@Configuration(proxyBeanMethods = false)
class BearerTokenSecurity {

    @Bean
    @Order(1)
    SecurityFilterChain bearerTokens(HttpSecurity http, AuthorizationResolver resolver, JwtDecoder toolTokens) throws Exception {
        http.securityMatcher(BearerTokenSecurity::registryWithToken)
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // No cookie carries these credentials: a cross-site request cannot use them
            .csrf(AbstractHttpConfigurer::disable)
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt
                .decoder(toolTokens)
                .jwtAuthenticationConverter(token -> authentication(token, resolver))));
        return http.build();
    }

    private static boolean registryWithToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        return request.getRequestURI().startsWith("/registry/") && authorization != null
                && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
    }

    /** The issuer is read when the first token arrives: the application starts without the identity provider. */
    @Bean
    JwtDecoder toolTokens(@Value("${spring.security.oauth2.client.provider.iam.issuer-uri}") String issuer,
                          @Value("${odt.security.bearer.clients:opendevtrack-tools}") List<String> clients) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
            OAuth2TokenValidator<Jwt> client = token -> clients.contains(token.getClaimAsString("azp"))
                    ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token issued to another client", null));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), client));
            return decoder;
        });
    }

    private static JwtAuthenticationToken authentication(Jwt token, AuthorizationResolver resolver) {
        String username = token.getClaimAsString("preferred_username");
        try {
            List<SimpleGrantedAuthority> permissions = resolver.resolve(username, null).permissions().stream()
                    .map(SimpleGrantedAuthority::new).toList();
            return new JwtAuthenticationToken(token, permissions, username);
        } catch (UserNotAuthorizedException e) {
            throw new BadCredentialsException("User not authorized");
        }
    }
}
