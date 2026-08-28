package net.caimito.mcpcli.discovery;

import net.caimito.mcpcli.config.CliProperties;
import net.caimito.mcpcli.mcp.McpRegistrar;
import net.caimito.mcpcli.process.ProcessRunner;
import net.caimito.mcpcli.protocol.CliProtocol;
import net.caimito.mcpcli.protocol.ProtocolValidator;
import net.caimito.mcpcli.registry.CliRegistry;
import net.caimito.mcpcli.registry.CliRegistry.CliContribution;
import net.caimito.mcpcli.registry.CliRegistry.Fingerprint;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CliReconciler {
    private static final Logger log = LoggerFactory.getLogger(CliReconciler.class);
    private final CliProperties properties; private final ProcessRunner processes; private final ProtocolValidator validator;
    private final CliRegistry registry; private final McpRegistrar registrar; private final Counter failures;
    private final AtomicBoolean scheduled = new AtomicBoolean(); private final AtomicReference<String> state = new AtomicReference<>("starting");
    private volatile boolean running = true; private Path directory;

    public CliReconciler(CliProperties properties, ProcessRunner processes, ProtocolValidator validator, CliRegistry registry,
                         McpRegistrar registrar, MeterRegistry metrics) {
        this.properties = properties; this.processes = processes; this.validator = validator; this.registry = registry; this.registrar = registrar;
        this.failures = metrics.counter("mcp_cli_discovery_failures_total");
        metrics.gauge("mcp_cli_active_clis", registry, value -> value.current().clis().size());
        metrics.gauge("mcp_cli_active_tools", registry, value -> value.current().tools().size());
    }

    @PostConstruct
    void initialize() {
        try {
            if (!properties.directory().isAbsolute()) throw new IllegalStateException("mcp.cli.directory must be an absolute path");
            directory = properties.directory().toRealPath();
            if (!Files.isDirectory(directory) || !Files.isReadable(directory)) throw new IllegalStateException("mcp.cli.directory must be a readable directory");
            reconcile(); state.set("up"); startWatcher();
            log.info("CLI discovery started for {} with {} active tools", directory, registry.current().tools().size());
        } catch (IOException | SecurityException ex) {
            throw new IllegalStateException("mcp.cli.directory must exist and be a readable directory: " + properties.directory(), ex);
        }
    }

    @Scheduled(fixedDelayString = "${mcp.cli.reconciliation-interval}")
    public void scheduledReconcile() { if (running) reconcile(); }

    public void reconcile() {
        try {
            if (directory == null || !Files.isDirectory(directory) || !Files.isReadable(directory)) throw new IOException("directory unavailable");
            List<CliContribution> candidates = new ArrayList<>();
            try (var paths = Files.list(directory)) {
                paths.sorted(Comparator.comparing(path -> path.getFileName().toString())).forEach(path -> probe(path, candidates));
            }
            Map<String, List<CliContribution>> byName = new HashMap<>();
            candidates.forEach(candidate -> byName.computeIfAbsent(candidate.descriptor().name(), ignored -> new ArrayList<>()).add(candidate));
            List<CliContribution> active = new ArrayList<>();
            byName.forEach((name, contributions) -> {
                if (contributions.size() == 1) active.add(contributions.getFirst());
                else { failures.increment(); log.warn("Ignoring duplicate CLI namespace {} from {}", name, contributions.stream().map(c -> c.executable().getFileName()).toList()); }
            });
            CliRegistry.Snapshot before = registry.current(); CliRegistry.Snapshot after = registry.replace(active);
            registrar.publish(after); state.set("up");
            if (!before.tools().keySet().equals(after.tools().keySet())) log.info("CLI registry now has {} tools", after.tools().size());
        } catch (Exception ex) {
            state.set("down"); failures.increment(); registry.replace(List.of()); registrar.publish(registry.current());
            log.warn("CLI reconciliation could not access the configured directory: {}", ex.getMessage());
        }
    }

    private void probe(Path candidate, List<CliContribution> results) {
        try {
            if (candidate.getFileName().toString().startsWith(".") || Files.isSymbolicLink(candidate)
                    || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(candidate)) return;
            Path real = candidate.toRealPath();
            if (!real.getParent().equals(directory) || Files.isSymbolicLink(real)) return;
            BasicFileAttributes before = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            ProcessRunner.ProcessResult call = processes.describe(real);
            BasicFileAttributes after = Files.readAttributes(real, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!same(before, after)) { log.debug("Skipping unstable CLI candidate {}", candidate.getFileName()); return; }
            if (!call.success()) { failures.increment(); log.warn("CLI descriptor probe failed for {}: {}", candidate.getFileName(), call.failureCode()); return; }
            CliProtocol.CliDescriptor descriptor = validator.descriptor(call.stdoutText());
            String globalPrefix = descriptor.name() + "_";
            for (CliProtocol.CliToolDescriptor tool : descriptor.tools()) {
                if ((globalPrefix + tool.name()).length() > 128) throw new IllegalArgumentException("global MCP tool name is too long");
            }
            results.add(new CliContribution(real, new Fingerprint(after.fileKey(), after.size(), after.lastModifiedTime().toMillis()), descriptor));
        } catch (Exception ex) { failures.increment(); log.warn("Ignoring invalid CLI candidate {}: {}", candidate.getFileName(), ex.getMessage()); }
    }

    private void startWatcher() {
        Thread.ofVirtual().name("mcp-cli-watch").start(() -> {
            try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
                directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE, StandardWatchEventKinds.ENTRY_MODIFY);
                while (running) { WatchKey key = watcher.take(); key.pollEvents(); key.reset(); requestReconcile(); }
            } catch (Exception ex) { if (running) { state.set("down"); log.warn("CLI directory watcher stopped: {}", ex.getMessage()); } }
        });
    }

    private void requestReconcile() {
        if (!scheduled.compareAndSet(false, true)) return;
        Thread.ofVirtual().start(() -> {
            try { Thread.sleep(properties.changeDebounce()); if (running) reconcile(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { scheduled.set(false); }
        });
    }

    private static boolean same(BasicFileAttributes a, BasicFileAttributes b) { return a.size() == b.size() && a.lastModifiedTime().equals(b.lastModifiedTime()) && java.util.Objects.equals(a.fileKey(), b.fileKey()); }
    public String state() { return state.get(); }
    @PreDestroy void shutdown() { running = false; }
}
