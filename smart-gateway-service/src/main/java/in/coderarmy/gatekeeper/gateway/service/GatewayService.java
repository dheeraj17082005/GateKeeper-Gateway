package in.coderarmy.gatekeeper.gateway.service;

import in.coderarmy.gatekeeper.gateway.exception.GatewayPolicyException;
import in.coderarmy.gatekeeper.gateway.model.CompiledRoute;
import in.coderarmy.gatekeeper.gateway.dto.GatewayErrorResponse;
import in.coderarmy.gatekeeper.gateway.dto.GatewayRoute;
import in.coderarmy.gatekeeper.gateway.model.RateLimitResult;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.UUID;

@Service
public class GatewayService {

    private static final Set<String> TRANSPORT_HEADERS = Set.of(
            "connection", "content-length", "expect", "host", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade");
    private final RouteRegistry routeRegistry;
    private final ObjectMapper objectMapper;
    private final RateLimitService rateLimitService;
    private final IdempotencyService idempotencyService;
    private final Set<String> allowedOrigins;
    private final UpstreamClient upstream = new UpstreamClient();

    public GatewayService(RouteRegistry routeRegistry, ObjectMapper objectMapper,
                          RateLimitService rateLimitService, IdempotencyService idempotencyService,
                          @Value("${gateway.allowed-upstreams}") String allowedUpstreams) {
        this.routeRegistry = routeRegistry;
        this.objectMapper = objectMapper;
        this.rateLimitService = rateLimitService;
        this.idempotencyService = idempotencyService;
        this.allowedOrigins = Arrays.stream(allowedUpstreams.split(","))
                .map(String::strip)
                .map(URI::create)
                .map(UpstreamClient::origin)
                .collect(Collectors.toUnmodifiableSet());
    }

    public ResponseEntity<byte[]> handle(HttpServletRequest request) {
        String correlationId = correlationId(request);
        Optional<CompiledRoute> match = routeRegistry.find(request.getRequestURI());
        if (match.isEmpty()) {
            return error(HttpStatus.NOT_FOUND, "No active route matches this request", correlationId, null);
        }

        CompiledRoute compiled = match.get();
        GatewayRoute route = compiled.getGatewayRoute();
        if (!route.getAllowedMethods().contains(request.getMethod())) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.ALLOW, String.join(", ", route.getAllowedMethods()));
            return error(HttpStatus.METHOD_NOT_ALLOWED, "HTTP method is not allowed for this route",
                    correlationId, headers);
        }

        HttpHeaders policyHeaders = new HttpHeaders();
        try {
            RateLimitResult rateLimit = rateLimitService.check(route, request.getRemoteAddr());
            policyHeaders = rateLimitHeaders(rateLimit);
            if (!rateLimit.isAllowed()) {
                policyHeaders.set(HttpHeaders.RETRY_AFTER, String.valueOf(rateLimit.getRetryAfterSeconds()));
                return error(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded", correlationId, policyHeaders);
            }

            byte[] body = request.getInputStream().readNBytes(UpstreamClient.MAX_BODY_BYTES + 1);
            if (body.length > UpstreamClient.MAX_BODY_BYTES) {
                return error(HttpStatus.CONTENT_TOO_LARGE, "Request body exceeds 4 MiB", correlationId, policyHeaders);
            }

            String path = request.getRequestURI();
            String suffix = path.startsWith(compiled.getSourcePathPrefix())
                    ? path.substring(compiled.getSourcePathPrefix().length()) : "";
            URI target = UpstreamClient.target(route.getTargetBaseUrl(), route.getTargetPathPrefix(),
                    suffix, request.getQueryString());
            if (!allowedOrigins.contains(UpstreamClient.origin(target))) {
                return error(HttpStatus.BAD_GATEWAY, "Upstream is outside the configured allowlist",
                        correlationId, policyHeaders);
            }
            HttpHeaders requestHeaders = new HttpHeaders();
            Collections.list(request.getHeaderNames()).forEach(name ->
                    Collections.list(request.getHeaders(name)).forEach(value -> requestHeaders.add(name, value)));
            filterHeaders(requestHeaders, true);
            applyHeaderRules(route, requestHeaders, "REQUEST");
            requestHeaders.set("X-Correlation-ID", correlationId);

            HttpRequest.Builder outgoing = HttpRequest.newBuilder(target)
                    .method(request.getMethod(), body.length == 0
                            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            requestHeaders.forEach((name, values) -> values.forEach(value -> outgoing.header(name, value)));
            if (requestHeaders.get(HttpHeaders.USER_AGENT) == null) {
                outgoing.header("User-Agent", "Gatekeeper-Gateway/1.0");
            }
            HttpRequest upstreamRequest = outgoing.build();

            if (route.isIdempotencyEnabled() && "POST".equals(request.getMethod())) {
                idempotencyService.reserve(route, request.getRemoteAddr(), request.getHeader("Idempotency-Key"));
            }

            var response = upstream.send(upstreamRequest, route.getResponseTimeoutMs());
            HttpHeaders headers = new HttpHeaders();
            response.headers().map().forEach((name, values) ->
                    values.forEach(value -> headers.add(name, value)));
            filterHeaders(headers, false);
            applyHeaderRules(route, headers, "RESPONSE");
            headers.putAll(policyHeaders);
            headers.set("X-Correlation-ID", correlationId);
            return ResponseEntity.status(response.statusCode()).headers(headers).body(response.body());
        } catch (GatewayPolicyException exception) {
            return error(exception.getStatus(), exception.getMessage(), correlationId, policyHeaders);
        } catch (IOException exception) {
            return error(HttpStatus.BAD_REQUEST, "Unable to read request body", correlationId, policyHeaders);
        } catch (TimeoutException exception) {
            return error(HttpStatus.GATEWAY_TIMEOUT, "Upstream timed out", correlationId, policyHeaders);
        } catch (ExecutionException exception) {
            HttpStatus status = exception.getCause() instanceof HttpTimeoutException
                    ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
            return error(status, "Upstream request failed", correlationId, policyHeaders);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return error(HttpStatus.SERVICE_UNAVAILABLE, "Gateway request interrupted", correlationId, policyHeaders);
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "Invalid forwarding request", correlationId, policyHeaders);
        }
    }

    private void filterHeaders(HttpHeaders headers, boolean request) {
        Set<String> removed = new HashSet<>(TRANSPORT_HEADERS);
        for (String value : headers.getOrEmpty(HttpHeaders.CONNECTION)) {
            for (String name : value.split(",")) {
                removed.add(name.strip().toLowerCase(Locale.ROOT));
            }
        }
        for (String name : Set.copyOf(headers.headerNames())) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (removed.contains(lower) || request && isForwardingHeader(lower)) {
                headers.remove(name);
            }
        }
    }

    private boolean isForwardingHeader(String name) {
        return name.equals("forwarded") || name.startsWith("x-forwarded-");
    }

    private void applyHeaderRules(GatewayRoute route, HttpHeaders headers, String direction) {
        for (var rule : route.getHeaderRules()) {
            String name = rule.getHeaderName();
            String lower = name.toLowerCase(Locale.ROOT);
            if (!direction.equals(rule.getDirection()) || TRANSPORT_HEADERS.contains(lower)
                    || isForwardingHeader(lower)) {
                continue;
            }
            if ("REMOVE".equals(rule.getAction())) {
                headers.remove(name);
            } else if ("ADD".equals(rule.getAction())) {
                headers.add(name, rule.getHeaderValue());
            }
        }
    }

    private String correlationId(HttpServletRequest request) {
        String correlationId = request.getHeader("X-Correlation-ID");

        if(correlationId == null || !correlationId.matches("[A-Za-z0-9._-]{1,128}")) {
            return UUID.randomUUID().toString();
        }
        return correlationId;
    }

    private HttpHeaders rateLimitHeaders(RateLimitResult result) {
        HttpHeaders headers = new HttpHeaders();
        if(result.getLimit() > 0) {
            headers.set("X-RateLimit-Limit", String.valueOf(result.getLimit()));
            headers.set("X-RateLimit-Remaining", String.valueOf(result.getRemaining()));
        }
        return headers;
    }

    private ResponseEntity<byte[]> error(HttpStatus status,
                                         String message,
                                         String correlationId,
                                         HttpHeaders additionalHeaders) {

        GatewayErrorResponse response = new GatewayErrorResponse(status.value(), message,
                correlationId, LocalDateTime.now());

        HttpHeaders headers = additionalHeaders == null ? new HttpHeaders() : additionalHeaders;

        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-ID", correlationId);

        return ResponseEntity.status(status).headers(headers)
                .body(objectMapper.writeValueAsString(response).getBytes(StandardCharsets.UTF_8));
    }
}
