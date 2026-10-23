package de.gbv.logguy.server.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import de.gbv.logguy.common.IngestProtocol;
import de.gbv.logguy.server.config.LogGuyProperties;

/**
 * Security of the web endpoints. Until users exist, only the stream API and the health endpoint are reachable.
 */
@Configuration
@Profile("!cli")
public class SecurityConfig {

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
     * Everything else: health is public, the rest is closed until users and rights exist.
     *
     * @param http the builder
     * @return the filter chain
     */
    @Bean
    @Order(2)
    public SecurityFilterChain defaultChain(HttpSecurity http) {
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.requestMatchers("/actuator/health/**", "/actuator/health")
                .permitAll()
                .anyRequest()
                .denyAll())
            .exceptionHandling(ex -> ex.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .build();
    }
}
