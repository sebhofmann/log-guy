package de.gbv.logguy.server.web;

import java.io.FilterOutputStream;
import java.io.IOException;

import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import jakarta.servlet.http.HttpServletResponse;

/**
 * A streaming body whose {@code flush()} reaches the client. In Spring Framework 7 the stream handed to a
 * {@link StreamingResponseBody} ignores {@code flush()}, so a stream of events would stay in the response buffer
 * until it ends. Here {@code flush()} flushes the servlet response.
 */
public final class FlushingBody {

    private FlushingBody() {
    }

    /**
     * Wraps a body so that its flushes reach the client.
     *
     * @param response the servlet response of the request
     * @param body the body
     * @return the wrapped body
     */
    public static StreamingResponseBody of(HttpServletResponse response, StreamingResponseBody body) {
        return out -> body.writeTo(new FilterOutputStream(out) {

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                response.flushBuffer();
            }
        });
    }
}
