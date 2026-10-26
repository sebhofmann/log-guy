package de.gbv.logguy.server.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.Map;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void ignoresDisconnectedClient() {
        IOException broken = new AsyncRequestNotUsableException("ServletResponse failed to flushBuffer",
            new ClientAbortException(new IOException("Broken pipe")));
        assertNull(handler.unreachable(broken));
    }

    @Test
    void reportsUnreachableStorage() {
        ResponseEntity<Map<String, String>> response = handler.unreachable(new IOException("Connection refused"));
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }
}
