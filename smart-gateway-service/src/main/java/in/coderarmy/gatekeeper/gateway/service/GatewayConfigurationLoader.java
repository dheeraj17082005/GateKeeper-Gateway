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
            GatewayRoute fallbackProducts = new GatewayRoute();
            fallbackProducts.setId(1L);
            fallbackProducts.setRouteId("demo-products");
            fallbackProducts.setName("Demo Products API");
            fallbackProducts.setPathPattern("/demo/products/**");
            fallbackProducts.setTargetBaseUrl("https://dummyjson.com");
            fallbackProducts.setTargetPathPrefix("/products");
            fallbackProducts.setAllowedMethods(java.util.Set.of("GET"));
            fallbackProducts.setActive(true);
            fallbackProducts.setResponseTimeoutMs(15000);

            GatewayRoute fallbackPosts = new GatewayRoute();
            fallbackPosts.setId(2L);
            fallbackPosts.setRouteId("demo-posts");
            fallbackPosts.setName("Demo Posts API");
            fallbackPosts.setPathPattern("/demo/posts/**");
            fallbackPosts.setTargetBaseUrl("https://jsonplaceholder.typicode.com");
            fallbackPosts.setTargetPathPrefix("/posts");
            fallbackPosts.setAllowedMethods(java.util.Set.of("GET"));
            fallbackPosts.setActive(true);
            fallbackPosts.setResponseTimeoutMs(15000);

            routeRegistry.replaceAll(List.of(fallbackProducts, fallbackPosts));
            log.info("Initialized fallback standalone routes: /demo/products/** and /demo/posts/**");
        }
    }
}
