package de.gbv.logguy.server.script;

import java.io.OutputStream;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/**
 * GraalJS for all scripts: one shared engine, one sandboxed context per thread and script, a watchdog that cancels
 * calls running longer than {@code event-timeout}.
 * <p>
 * Contexts run with {@link SandboxPolicy#CONSTRAINED}: no file, network or Java access, no threads, output discarded.
 * A heap limit per context ({@code sandbox.MaxHeapMemory}) exists only in Oracle GraalVM, not in the community
 * edition used here.
 */
public class ScriptRuntime implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String JS = "js";

    private static final String MODULE_MIME_TYPE = "application/javascript+module";

    private final Engine engine;

    private final long timeoutNanos;

    private final TimeParser timeParser = new TimeParser();

    private final Set<Call> running = ConcurrentHashMap.newKeySet();

    private final Thread watchdog;

    private volatile boolean closed;

    /**
     * Creates the runtime.
     *
     * @param eventTimeout time after which a single call is cancelled
     */
    public ScriptRuntime(Duration eventTimeout) {
        this.engine = Engine.newBuilder(JS)
            .sandbox(SandboxPolicy.CONSTRAINED)
            .out(OutputStream.nullOutputStream())
            .err(OutputStream.nullOutputStream())
            .option("engine.WarnInterpreterOnly", "false")
            .build();
        this.timeoutNanos = eventTimeout.toNanos();
        this.watchdog = Thread.ofPlatform().daemon().name("script-watchdog").start(this::watch);
        LOGGER.info("GraalJS {} on {} {}, runtime compilation {}", engine.getVersion(),
            System.getProperty("java.vm.name"), System.getProperty("java.vm.version"),
            engine.getImplementationName());
    }

    /**
     * Creates a new sandboxed context with the built-in functions.
     *
     * @return the context, to be closed by the caller
     */
    public Context newContext() {
        Context context = Context.newBuilder(JS)
            .engine(engine)
            .sandbox(SandboxPolicy.CONSTRAINED)
            .out(OutputStream.nullOutputStream())
            .err(OutputStream.nullOutputStream())
            .option("js.esm-eval-returns-exports", "true")
            .build();
        context.getBindings(JS).putMember("parseTime", (ProxyExecutable) args -> {
            if (args.length < 3 || !args[0].isString()) {
                return null;
            }
            return timeParser.parse(args[0].asString(), args[1].asString(), args[2].asString());
        });
        return context;
    }

    /**
     * Creates a source for an ES module.
     *
     * @param name name shown in error messages, e.g. {@code parser/digibib.js}
     * @param code the code
     * @return the source
     */
    public static Source module(String name, String code) {
        return Source.newBuilder(JS, code, name).mimeType(MODULE_MIME_TYPE).buildLiteral();
    }

    /**
     * Starts watching a call. The call is interrupted if it does not end within {@code event-timeout}.
     *
     * @param context the context the call runs in
     * @return the handle, to be ended with {@link #end(Call)}
     */
    Call start(Context context) {
        Call call = new Call(context, System.nanoTime() + timeoutNanos);
        running.add(call);
        return call;
    }

    /**
     * Ends watching a call.
     *
     * @param call the handle
     * @return true if the watchdog interrupted the call
     */
    boolean end(Call call) {
        running.remove(call);
        return call.interrupted;
    }

    private void watch() {
        long sleep = Math.max(1_000_000, timeoutNanos / 5);
        while (!closed) {
            try {
                Thread.sleep(Duration.ofNanos(sleep));
            } catch (InterruptedException e) {
                return;
            }
            long now = System.nanoTime();
            for (Call call : running) {
                if (!call.interrupted && now - call.deadline > 0) {
                    call.interrupted = true;
                    Thread.ofVirtual().start(() -> interrupt(call));
                }
            }
        }
    }

    private static void interrupt(Call call) {
        try {
            call.context.interrupt(Duration.ofSeconds(5));
        } catch (Exception e) {
            LOGGER.warn("cannot interrupt script", e);
        }
    }

    @Override
    public void close() {
        closed = true;
        watchdog.interrupt();
        engine.close();
    }

    /**
     * A running script call.
     */
    static final class Call {

        private final Context context;

        private final long deadline;

        private volatile boolean interrupted;

        private Call(Context context, long deadline) {
            this.context = context;
            this.deadline = deadline;
        }
    }
}
