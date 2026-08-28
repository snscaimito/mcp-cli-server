package net.caimito.mcpcli.process;

import net.caimito.mcpcli.config.CliProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

@Component
public class ProcessRunner implements AutoCloseable {
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(2);
    private final CliProperties properties;
    private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Process> active = ConcurrentHashMap.newKeySet();

    public ProcessRunner(CliProperties properties) { this.properties = properties; }

    public ProcessResult describe(Path executable) { return run(executable, List.of("mcp", "describe"), null, properties.discoveryTimeout()); }

    public ProcessResult invoke(Path executable, String input) { return run(executable, List.of("mcp", "invoke"), input, properties.invocationTimeout()); }

    private ProcessResult run(Path executable, List<String> arguments, String input, Duration timeout) {
        Instant started = Instant.now();
        Process process = null;
        try {
            List<String> command = new ArrayList<>(); command.add(executable.toString()); command.addAll(arguments);
            ProcessBuilder builder = new ProcessBuilder(command).directory(properties.directory().toFile());
            Map<String, String> environment = builder.environment(); environment.clear();
            for (String key : properties.environmentAllowlist()) {
                String value = System.getenv(key); if (value != null) environment.put(key, value);
            }
            process = builder.start(); active.add(process);
            AtomicBoolean stdoutExceeded = new AtomicBoolean(); AtomicBoolean stderrExceeded = new AtomicBoolean();
            Process running = process;
            var stdout = streams.submit(() -> readBounded(running, running.getInputStream(), properties.maxStdoutBytes(), stdoutExceeded));
            var stderr = streams.submit(() -> readBounded(running, running.getErrorStream(), properties.maxStderrBytes(), stderrExceeded));
            if (input != null) {
                try (OutputStream stdin = process.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
            } else { process.getOutputStream().close(); }
            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited || stdoutExceeded.get() || stderrExceeded.get()) terminate(process);
            byte[] out = stdout.get(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            byte[] err = stderr.get(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            if (stdoutExceeded.get() || stderrExceeded.get()) return ProcessResult.failure("CLI_OUTPUT_TOO_LARGE", out, err, Duration.between(started, Instant.now()));
            if (!exited) return ProcessResult.failure("CLI_TIMEOUT", out, err, Duration.between(started, Instant.now()));
            if (process.exitValue() != 0) return ProcessResult.failure("CLI_EXITED_NONZERO", out, err, Duration.between(started, Instant.now()));
            return ProcessResult.success(out, err, Duration.between(started, Instant.now()));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); if (process != null) terminate(process);
            return ProcessResult.failure("CLI_CANCELLED", new byte[0], new byte[0], Duration.between(started, Instant.now()));
        } catch (Exception ex) {
            if (process != null) terminate(process);
            return ProcessResult.failure("CLI_UNAVAILABLE", new byte[0], new byte[0], Duration.between(started, Instant.now()));
        } finally { if (process != null) active.remove(process); }
    }

    private static byte[] readBounded(Process process, InputStream stream, long limit, AtomicBoolean exceeded) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (stream; bytes) {
            byte[] chunk = new byte[8192]; int count;
            while ((count = stream.read(chunk)) != -1) {
                int permitted = (int) Math.min(count, Math.max(0, limit - bytes.size()));
                bytes.write(chunk, 0, permitted);
                if (permitted != count) { exceeded.set(true); process.destroy(); break; }
            }
            return bytes.toByteArray();
        } catch (IOException ex) {
            return bytes.toByteArray();
        }
    }

    private static void terminate(Process process) {
        process.destroy();
        try { if (!process.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) process.destroyForcibly(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
    }

    @Override public void close() { active.forEach(ProcessRunner::terminate); streams.close(); }

    public record ProcessResult(boolean success, String failureCode, byte[] stdout, byte[] stderr, Duration duration) {
        static ProcessResult success(byte[] out, byte[] err, Duration duration) { return new ProcessResult(true, null, out, err, duration); }
        static ProcessResult failure(String code, byte[] out, byte[] err, Duration duration) { return new ProcessResult(false, code, out, err, duration); }
        public String stdoutText() { return new String(stdout, StandardCharsets.UTF_8); }
    }
}
