package in.coderarmy.gatekeeper.gateway.service;

import in.coderarmy.gatekeeper.gateway.client.ManagementApiClient;
import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class GatewayConfigurationLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfigurationLoader.class);

    private final ManagementApiClient managementApiClient;
    private final RouteRegistry routeRegistry;

    public GatewayConfigurationLoader(ManagementApiClient managementApiClient,
                                      RouteRegistry routeRegistry) {

        this.managementApiClient = managementApiClient;
        this.routeRegistry = routeRegistry;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        reconcile();
    }

    @Scheduled(fixedDelayString = "${gateway.configuration.reconcile-ms:30000}")
    public void reconcile() {
        try {
            refresh();
        } catch (RuntimeException exception) {
            log.warn("Gateway route refresh failed ({})", exception.getClass().getSimpleName());
        }
    }

    public synchronized void refresh() {
        List<GatewayRoute> routes = managementApiClient.fetchActiveRoutes();

        routeRegistry.replaceAll(routes);
    }
}
