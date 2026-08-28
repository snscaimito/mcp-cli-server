package net.caimito.mcpcli.discovery;

import net.caimito.mcpcli.registry.CliRegistry;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("cli")
public class CliHealthIndicator implements HealthIndicator {
    private final CliReconciler reconciler; private final CliRegistry registry;
    public CliHealthIndicator(CliReconciler reconciler, CliRegistry registry) { this.reconciler = reconciler; this.registry = registry; }
    @Override public Health health() {
        if (!"up".equals(reconciler.state())) return Health.down().withDetail("reconciliation", reconciler.state()).build();
        return Health.up().withDetail("activeClis", registry.current().clis().size()).withDetail("activeTools", registry.current().tools().size()).build();
    }
}
