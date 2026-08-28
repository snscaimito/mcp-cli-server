package net.caimito.mcpcli.registry;

import net.caimito.mcpcli.protocol.CliProtocol;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class CliRegistry {
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(new Snapshot(Map.of(), Map.of()));
    public Snapshot current() { return snapshot.get(); }
    public Snapshot replace(Collection<CliContribution> contributions) {
        Map<String, CliContribution> clis = new LinkedHashMap<>(); Map<String, RegisteredTool> tools = new LinkedHashMap<>();
        for (CliContribution cli : contributions) {
            clis.put(cli.descriptor().name(), cli);
            for (CliProtocol.CliToolDescriptor tool : cli.descriptor().tools()) {
                String global = cli.descriptor().name() + "_" + tool.name();
                tools.put(global, new RegisteredTool(global, cli, tool));
            }
        }
        Snapshot next = new Snapshot(Map.copyOf(clis), Map.copyOf(tools)); snapshot.set(next); return next;
    }
    public record Snapshot(Map<String, CliContribution> clis, Map<String, RegisteredTool> tools) { }
    public record CliContribution(Path executable, Fingerprint fingerprint, CliProtocol.CliDescriptor descriptor) { }
    public record RegisteredTool(String globalName, CliContribution cli, CliProtocol.CliToolDescriptor tool) { }
    public record Fingerprint(Object fileKey, long size, long modifiedMillis) { }
}
