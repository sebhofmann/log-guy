package de.gbv.logguy.server.script;

import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SandboxPolicy;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/**
 * GraalJS for all scripts: one shared engine, one sandboxed context per thread and script, a watchdog that cancels
 * calls running longer than {@code event-timeout} and module evaluations running longer than {@code load-timeout}.
 * <p>
 * Contexts run with {@link SandboxPolicy#CONSTRAINED}: no file, network or Java access, no threads, output discarded.
 * Limits for heap and CPU time per context ({@code sandbox.MaxHeapMemory}, {@code sandbox.MaxCPUTime}) exist only in
 * Oracle GraalVM, not in the community edition used here. Memory is therefore only limited indirectly by the time
 * limits.
 */
public class ScriptRuntime implements AutoCloseable {

    private static final Logger LOGGER = LogManager.getLogger();

    private static final String JS = "js";

    private static final String MODULE_MIME_TYPE = "application/javascript+module";

    private final Engine engine;

    private final long timeoutNanos;

    private final Duration loadTimeout;

    private static final ThreadLocal<BinaryOperator<String>> PSEUDONYMS = new ThreadLocal<>();

    private static final ThreadLocal<Instant> PREVIOUS_TIME = new ThreadLocal<>();

    private final TimeParser timeParser = new TimeParser();

    private final Set<Call> running = ConcurrentHashMap.newKeySet();

    private final Thread watchdog;

    private volatile boolean closed;

    /**
     * Creates the runtime.
     *
     * @param eventTimeout time after which a single call is cancelled
     * @param loadTimeout time after which the evaluation of a module, i.e. its top level code, is cancelled
     */
    public ScriptRuntime(Duration eventTimeout, Duration loadTimeout) {
        this.engine = Engine.newBuilder(JS)
            .sandbox(SandboxPolicy.CONSTRAINED)
            .out(OutputStream.nullOutputStream())
            .err(OutputStream.nullOutputStream())
            .option("engine.WarnInterpreterOnly", "false")
            .build();
        this.timeoutNanos = eventTimeout.toNanos();
        this.loadTimeout = loadTimeout;
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
            return timeParser.parse(args[0].asString(), args[1].asString(), args[2].asString(),
                PREVIOUS_TIME.get());
        });
        Value bindings = context.getBindings(JS);
        bindings.putMember("maskIp", (ProxyExecutable) args -> TextFunctions.maskIp(text(args)));
        bindings.putMember("findIps", (ProxyExecutable) args -> array(TextFunctions.findIps(text(args))));
        bindings.putMember("findEmails", (ProxyExecutable) args -> array(TextFunctions.findEmails(text(args))));
        bindings.putMember("replaceIps",
            (ProxyExecutable) args -> TextFunctions.replaceIps(text(args), callback(args)));
        bindings.putMember("replaceEmails",
            (ProxyExecutable) args -> TextFunctions.replaceEmails(text(args), callback(args)));
        bindings.putMember("ps", (ProxyExecutable) args -> {
            BinaryOperator<String> pseudonyms = PSEUDONYMS.get();
            if (pseudonyms == null) {
                throw new IllegalStateException("ps() is only available in pseudonymization scripts");
            }
            if (args.length < 2) {
                throw new IllegalArgumentException("ps(category, value) needs two arguments");
            }
            Value value = args[1];
            return value.isNull() ? null : pseudonyms.apply(args[0].asString(), value.isString()
                ? value.asString() : value.toString());
        });
        return context;
    }

    /**
     * Sets the time of the previous event of the same file on this thread, {@code parseTime} resolves the repeated
     * hour at the end of daylight saving time with it.
     *
     * @param previous the time, {@code null} to remove it
     */
    static void previousTime(Instant previous) {
        if (previous == null) {
            PREVIOUS_TIME.remove();
        } else {
            PREVIOUS_TIME.set(previous);
        }
    }

    /**
     * Runs code with the pseudonyms {@code ps(category, value)} uses on this thread.
     *
     * @param pseudonyms maps category and value to the pseudonym
     * @param action the code
     * @param <T> the result type
     * @return the result of the action
     */
    public static <T> T withPseudonyms(BinaryOperator<String> pseudonyms, Supplier<T> action) {
        BinaryOperator<String> before = PSEUDONYMS.get();
        PSEUDONYMS.set(pseudonyms);
        try {
            return action.get();
        } finally {
            if (before == null) {
                PSEUDONYMS.remove();
            } else {
                PSEUDONYMS.set(before);
            }
        }
    }

    private static String text(Value[] args) {
        if (args.length == 0 || args[0].isNull()) {
            return null;
        }
        return args[0].isString() ? args[0].asString() : args[0].toString();
    }

    private static UnaryOperator<String> callback(Value[] args) {
        if (args.length < 2 || !args[1].canExecute()) {
            throw new IllegalArgumentException("the second argument must be a function");
        }
        Value function = args[1];
        return found -> {
            Value result = function.execute(found);
            return result.isString() ? result.asString() : result.toString();
        };
    }

    private static ProxyArray array(List<String> values) {
        return ProxyArray.fromList(new ArrayList<>(values));
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
     * Evaluates an ES module under the watchdog. Its top level code is cancelled if it does not end within
     * {@code load-timeout}. Every module evaluation goes through here, so a script cannot block a thread with code
     * outside its functions.
     *
     * @param context the context to evaluate the module in
     * @param label name of the module without {@code .js}, e.g. {@code parser/digibib}, used in error messages
     * @param code the code
     * @return the exports of the module
     * @throws ScriptException if the module throws, has a syntax error or runs too long
     */
    public Value evalModule(Context context, String label, String code) throws ScriptException {
        Call call = start(context, loadTimeout.toNanos());
        try {
            Value exports = context.eval(module(label + ".js", code));
            if (end(call)) {
                // finished just before the watchdog fired, the interrupt may hit the next call of this context
                throw timeout(label);
            }
            return exports;
        } catch (PolyglotException e) {
            if (end(call)) {
                throw timeout(label);
            }
            throw new ScriptException(label + ": " + e.getMessage(), e);
        }
    }

    private ScriptException timeout(String label) {
        return new ScriptException(label + ": evaluating the module took longer than " + loadTimeout.toMillis()
            + " ms", null);
    }

    /**
     * Starts watching a call. The call is interrupted if it does not end within {@code event-timeout}.
     *
     * @param context the context the call runs in
     * @return the handle, to be ended with {@link #end(Call)}
     */
    public Call start(Context context) {
        return start(context, timeoutNanos);
    }

    private Call start(Context context, long nanos) {
        Call call = new Call(context, System.nanoTime() + nanos);
        running.add(call);
        return call;
    }

    /**
     * Ends watching a call.
     *
     * @param call the handle
     * @return true if the watchdog interrupted the call
     */
    public boolean end(Call call) {
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
    public static final class Call {

        private final Context context;

        private final long deadline;

        private volatile boolean interrupted;

        private Call(Context context, long deadline) {
            this.context = context;
            this.deadline = deadline;
        }
    }
}
