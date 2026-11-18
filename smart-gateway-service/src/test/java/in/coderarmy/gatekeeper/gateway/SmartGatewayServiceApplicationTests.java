package in.coderarmy.gatekeeper.gateway;

import in.coderarmy.gatekeeper.gateway.client.ManagementApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false"
})
class SmartGatewayServiceApplicationTests {

    @MockitoBean
    private ManagementApiClient managementApiClient;

    @Test
    void contextLoads() {
    }
}
