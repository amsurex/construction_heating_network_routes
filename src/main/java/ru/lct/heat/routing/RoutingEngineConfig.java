package ru.lct.heat.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Spring-обвязка ядра: параметры из {@code app.routing.*} и бин {@link RoutingEngine}.
 * Ядро само по себе от Spring не зависит (см. {@link HeatRoutingEngine}).
 */
@Configuration
public class RoutingEngineConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.routing")
    public RoutingParams routingParams() {
        return new RoutingParams();
    }

    @Bean
    @Primary
    public RoutingEngine routingEngine(RoutingParams params) {
        return new HeatRoutingEngine(params);
    }
}
