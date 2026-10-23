package de.gbv.logguy.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import de.gbv.logguy.common.Fingerprint;
import de.gbv.logguy.common.IngestProtocol;

/**
 * Minimal stream API in memory with the same duplicate handling as the server.
 */
class FakeServer implements AutoCloseable {

    static final String TOKEN = "secret";

    final Map<String, ByteArrayOutputStream> streams = new ConcurrentHashMap<>();

    final Map<String, Boolean> closed = new ConcurrentHashMap<>();

    final Map<String, List<String>> files = new ConcurrentHashMap<>();

    volatile int requests;

    private final HttpServer server;

    FakeServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(IngestProtocol.STREAMS_PATH, this::handle);
        server.start();
    }

    AgentConfig config(List<AgentConfig.Source> sources) {
        return new AgentConfig(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), TOKEN, "app1", 4096,
            Duration.ofMillis(100), Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(5),
            Duration.ofMillis(10), sources);
    }

    byte[] content(String fingerprint) {
        ByteArrayOutputStream stream = streams.get(fingerprint);
        return stream == null ? new byte[0] : stream.toByteArray();
    }

    private synchronized void handle(HttpExchange exchange) throws IOException {
        requests++;
        if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            send(exchange, 401, "{}");
            return;
        }
        String[] parts = exchange.getRequestURI().getPath().substring(IngestProtocol.STREAMS_PATH.length() + 1)
            .split("/");
        String fp = parts[0];
        String query = exchange.getRequestURI().getQuery();
        long value = query == null ? 0 : Long.parseLong(query.substring(query.indexOf('=') + 1));
        ByteArrayOutputStream stream = streams.get(fp);
        if (parts.length == 1) {
            if (stream == null) {
                send(exchange, 404, "{}");
            } else {
                send(exchange, 200, status(fp));
            }
            return;
        }
        if (parts[1].equals("chunks")) {
            byte[] data = exchange.getRequestBody().readAllBytes();
            if (stream == null) {
                if (value != 0) {
                    send(exchange, 409, "{\"fingerprint\":\"" + fp + "\",\"length\":0,\"closed\":false}");
                    return;
                }
                if (!Fingerprint.of(data).equals(fp)) {
                    send(exchange, 400, "{}");
                    return;
                }
                stream = new ByteArrayOutputStream();
                streams.put(fp, stream);
                closed.put(fp, false);
            }
            files.computeIfAbsent(fp, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(exchange.getRequestHeaders().getFirst(IngestProtocol.HEADER_FILE));
            long end = stream.size();
            if (value > end) {
                send(exchange, 409, status(fp));
                return;
            }
            int skip = (int) (end - value);
            if (skip < data.length) {
                if (closed.get(fp)) {
                    send(exchange, 409, status(fp));
                    return;
                }
                stream.write(data, skip, data.length - skip);
            }
            send(exchange, 200, status(fp));
            return;
        }
        if (stream == null) {
            send(exchange, 404, "{}");
        } else if (stream.size() != value) {
            send(exchange, 409, status(fp));
        } else {
            closed.put(fp, true);
            send(exchange, 200, status(fp));
        }
    }

    private String status(String fp) {
        return "{\"fingerprint\":\"" + fp + "\",\"length\":" + streams.get(fp).size() + ",\"closed\":"
            + closed.get(fp) + "}";
    }

    private static void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
