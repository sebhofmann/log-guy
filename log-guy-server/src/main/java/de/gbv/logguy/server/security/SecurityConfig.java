package de.gbv.logguy.server.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import de.gbv.logguy.common.IngestProtocol;
import de.gbv.logguy.server.config.LogGuyProperties;

/**
 * Security of the web endpoints: the stream API for agents, the API for API tokens and GUI sessions, and the GUI
 * with a login form.
 */
@Configuration
@Profile("!cli")
public class SecurityConfig {

    /**
     * Creates the password encoder, Argon2id with the parameters of Spring Security.
     *
     * @return the encoder
     */
    public static PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    /**
     * The password encoder for the login.
     *
     * @return the encoder
     */
    @Bean
    public PasswordEncoder loginPasswordEncoder() {
        return passwordEncoder();
    }

    /**
     * The configured tokens.
     *
     * @param properties configuration
     * @return the registry
     */
    @Bean
    public TokenRegistry tokenRegistry(LogGuyProperties properties) {
        return new TokenRegistry(properties.tokens());
    }

    /**
     * The configured users, also used for the login.
     *
     * @param properties configuration
     * @return the registry
     */
    @Bean
    public UserRegistry userRegistry(LogGuyProperties properties) {
        return new UserRegistry(properties.users());
    }

    /**
     * Resolves the user of a request.
     *
     * @param users the configured users
     * @return the resolver
     */
    @Bean
    public CurrentUser currentUser(UserRegistry users) {
        return new CurrentUser(users);
    }

    /**
     * The stream API, only for ingest tokens.
     *
     * @param http the builder
     * @param tokens the configured tokens
     * @return the filter chain
     */
    @Bean
    @Order(1)
    public SecurityFilterChain ingestChain(HttpSecurity http, TokenRegistry tokens) {
        return http.securityMatcher(IngestProtocol.STREAMS_PATH + "/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .addFilterBefore(new BearerTokenFilter(tokens), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("INGEST"))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .build();
    }

    /**
     * The API, for API tokens and for the GUI with its session. Requests with a token need no CSRF token.
     *
     * @param http the builder
     * @param tokens the configured tokens
     * @return the filter chain
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiChain(HttpSecurity http, TokenRegistry tokens) {
        return http.securityMatcher("/api/**")
            .csrf(csrf -> csrf.ignoringRequestMatchers(request -> request.getHeader(HttpHeaders.AUTHORIZATION) != null))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
            .addFilterBefore(new BearerTokenFilter(tokens), BasicAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("USER"))
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .build();
    }

    /**
     * The GUI with login form. Health and static files are public, the actuator endpoints need an administrator.
     *
     * @param http the builder
     * @param currentUser resolves the user
     * @return the filter chain
     */
    @Bean
    @Order(3)
    public SecurityFilterChain webChain(HttpSecurity http, CurrentUser currentUser) {
        AuthorizationManager<RequestAuthorizationContext> admin = (authentication, context) -> {
            try {
                return new AuthorizationDecision(currentUser.get().isAdmin());
            } catch (RuntimeException e) {
                return new AuthorizationDecision(false);
            }
        };
        return http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/static/**", "/favicon.ico", "/login",
                    "/error")
                .permitAll()
                .requestMatchers("/actuator/**", "/admin/**")
                .access(admin)
                .anyRequest()
                .hasRole("USER"))
            .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", false))
            .logout(logout -> logout.logoutUrl("/logout").logoutSuccessUrl("/login?logout"))
            .headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; img-src 'self' data:; style-src 'self'; frame-ancestors 'none'"))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.SAME_ORIGIN)))
            .build();
    }
}
