package de.gbv.logguy.server.mcp;

import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import de.gbv.logguy.server.LogGuyApplication;
import de.gbv.logguy.server.alert.AlertService;
import de.gbv.logguy.server.alert.AlertTexts;
import de.gbv.logguy.server.archive.search.ArchiveSearch;
import de.gbv.logguy.server.audit.AuditLog;
import de.gbv.logguy.server.config.LogGuyProperties;
import de.gbv.logguy.server.config.LogGuyProperties.McpProperties;
import de.gbv.logguy.server.pseudonym.PseudonymService;
import de.gbv.logguy.server.script.ScriptService;
import de.gbv.logguy.server.script.ScriptTester;
import de.gbv.logguy.server.search.ContextService;
import de.gbv.logguy.server.search.PseudonymizedSearch;
import de.gbv.logguy.server.search.SearchService;
import de.gbv.logguy.server.security.UserRegistry;
import de.gbv.logguy.server.serverlog.ServerLog;
import de.gbv.logguy.server.status.StatusService;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import tools.jackson.databind.json.JsonMapper;

/**
 * The MCP endpoint: transport with the authentication of the request in the MCP context, sessions and tools. The
 * MCP server itself comes from the Spring AI auto configuration.
 */
@Configuration
@Profile("!" + LogGuyApplication.CLI_PROFILE)
public class McpConfig {

    /**
     * Streamable HTTP on {@code log-guy.mcp.path}. The security filter has already checked the MCP token, its
     * authentication is handed to the tools through the transport context.
     *
     * @param properties configuration
     * @param json the JSON mapper
     * @return the transport
     */
    @Bean
    public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
        LogGuyProperties properties, JsonMapper json) {
        McpProperties mcp = properties.mcp();
        return WebMvcStreamableServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapper(json))
            .mcpEndpoint(mcp.path())
            .sessionIdleTimeout(mcp.sessionIdleTimeout())
            .maxSessions(mcp.maxSessions())
            .contextExtractor(request -> {
                Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                return authentication == null ? McpTransportContext.EMPTY
                    : McpTransportContext.create(Map.of(McpTools.AUTHENTICATION, authentication));
            })
            .build();
    }

    /**
     * The pseudonyms of the open sessions.
     *
     * @param properties configuration
     * @param clock the clock
     * @return the store
     */
    @Bean
    public McpSessions mcpSessions(LogGuyProperties properties, Clock clock) {
        McpProperties mcp = properties.mcp();
        return new McpSessions(mcp.sessionIdleTimeout(), mcp.maxSessions(), mcp.maxSessionsPerToken(), clock);
    }

    /**
     * Applies the session limits when a client initializes a session. Runs after the security filter chain.
     *
     * @param properties configuration, for the path of the endpoint
     * @param sessions the sessions
     * @return the registration
     */
    @Bean
    public FilterRegistrationBean<McpSessionFilter> mcpSessionFilter(LogGuyProperties properties,
        McpSessions sessions) {
        FilterRegistrationBean<McpSessionFilter> registration = new FilterRegistrationBean<>(
            new McpSessionFilter(sessions));
        registration.addUrlPatterns(properties.mcp().path(), properties.mcp().path() + "/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE);
        return registration;
    }

    /**
     * The tools, picked up by the MCP server of Spring AI.
     *
     * @param properties configuration
     * @param users finds the user of a token
     * @param sessions pseudonyms per session
     * @param search the search
     * @param context lines around an event
     * @param status the status
     * @param serverLog the own log
     * @param scripts the scripts
     * @param tester tests scripts
     * @param pseudonyms the pseudonymization
     * @param pseudonymizedSearch pseudonymizes search results
     * @param audit the audit log
     * @param archiveSearch the archive search
     * @param alerts the alerts
     * @param alertTexts pseudonymizes the alerts
     * @param json the JSON mapper
     * @return the tool specifications
     */
    @Bean
    public List<SyncToolSpecification> logGuyTools(LogGuyProperties properties, UserRegistry users,
        McpSessions sessions, SearchService search, ContextService context, StatusService status,
        ServerLog serverLog, ScriptService scripts, ScriptTester tester, PseudonymService pseudonyms,
        PseudonymizedSearch pseudonymizedSearch, AuditLog audit, ArchiveSearch archiveSearch, AlertService alerts,
        AlertTexts alertTexts, JsonMapper json) {
        return new McpTools(properties.mcp(), users, sessions, search, context, status, serverLog, scripts, tester,
            pseudonyms, pseudonymizedSearch, audit, archiveSearch, alerts, alertTexts, json,
            new JacksonMcpJsonMapper(json))
            .specifications();
    }
}
