package in.coderarmy.gatekeeper.gateway.event;

import in.coderarmy.gatekeeper.gateway.service.GatewayConfigurationLoader;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "gateway.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class ConfigurationEventConsumer {

    private final GatewayConfigurationLoader configurationLoader;

    public ConfigurationEventConsumer(GatewayConfigurationLoader configurationLoader) {
        this.configurationLoader = configurationLoader;
    }

    @KafkaListener(topics = "${gateway.kafka.configuration-topic}",
            groupId = "${gateway.kafka.configuration-group}")
    public void configurationChanged(String event) {
        configurationLoader.refresh();
    }
}
