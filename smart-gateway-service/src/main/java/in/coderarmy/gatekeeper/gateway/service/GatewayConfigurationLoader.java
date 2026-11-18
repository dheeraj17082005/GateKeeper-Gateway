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
        try {
            List<GatewayRoute> routes = managementApiClient.fetchActiveRoutes();
            if (routes != null && !routes.isEmpty()) {
                routeRegistry.replaceAll(routes);
                return;
            }
        } catch (RuntimeException exception) {
            log.warn("Management API route refresh failed ({})", exception.getClass().getSimpleName());
        }

        if (routeRegistry.find("/demo/products").isEmpty()) {
            GatewayRoute fallback = new GatewayRoute();
            fallback.setId(1L);
            fallback.setRouteId("demo-products");
            fallback.setName("Demo Products API");
            fallback.setPathPattern("/demo/products/**");
            fallback.setTargetBaseUrl("https://dummyjson.com");
            fallback.setTargetPathPrefix("/products");
            fallback.setAllowedMethods(java.util.Set.of("GET"));
            fallback.setActive(true);
            fallback.setResponseTimeoutMs(5000);
            routeRegistry.replaceAll(List.of(fallback));
            log.info("Initialized fallback standalone route: /demo/products/** -> https://dummyjson.com/products");
        }
    }
}
