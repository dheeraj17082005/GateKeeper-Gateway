package in.coderarmy.gatekeeper.gateway.service;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UpstreamClientTest {
    @Test
    void preservesRawSuffixAndQuery() {
        assertEquals(URI.create("https://backend.test/base/v1/some%20item?tag=a%2Bb&tag=c"),
                UpstreamClient.target("https://backend.test/base/", "/v1/", "/some%20item", "tag=a%2Bb&tag=c"));
    }

    @Test
    void keepsExactTargetPathForAnEmptySuffix() {
        assertEquals(URI.create("http://backend.test/catalog.json"),
                UpstreamClient.target("http://backend.test", "/catalog.json", "", null));
    }

    @Test
    void rejectsCredentialsAndDotSegments() {
        assertThrows(IllegalArgumentException.class,
                () -> UpstreamClient.target("http://user@backend.test", "/v1", "", null));
        assertThrows(IllegalArgumentException.class,
                () -> UpstreamClient.target("http://backend.test", "/v1", "/%2e%2e/items", null));
    }

    @Test
    void matchesExplicitAndImplicitDefaultPorts() {
        assertEquals(UpstreamClient.origin(URI.create("https://backend.test:443/path")),
                UpstreamClient.origin(URI.create("https://BACKEND.test")));
    }
}
