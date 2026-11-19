package in.coderarmy.gatekeeper.gateway.controller;

import in.coderarmy.gatekeeper.gateway.service.GatewayService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GatewayController {

    private final GatewayService gatewayService;

    public GatewayController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @org.springframework.web.bind.annotation.GetMapping(value = "/", produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
    public org.springframework.core.io.Resource rootHtml() {
        return new org.springframework.core.io.ClassPathResource("static/index.html");
    }

    @org.springframework.web.bind.annotation.GetMapping(value = "/", produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<java.util.Map<String, Object>> rootJson() {
        return ResponseEntity.ok(java.util.Map.of(
                "name", "Gatekeeper API Gateway",
                "appStatus", "UP",
                "healthCheck", "/health",
                "demoEndpoints", java.util.List.of("/demo/products/1", "/demo/posts/1")
        ));
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) {
        return gatewayService.handle(request);
    }
}
