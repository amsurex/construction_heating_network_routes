package ru.lct.heat.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import ru.lct.heat.routing.RoutingEngine;
import ru.lct.heat.routing.stub.StubRoutingEngine;

@Configuration
@EnableAsync
public class PlatformConfig {
    @Bean("jobExecutor")
    public ThreadPoolTaskExecutor jobExecutor(PlatformProperties properties) {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(1);
        pool.setMaxPoolSize(1);
        pool.setQueueCapacity(properties.getQueueCapacity());
        pool.setThreadNamePrefix("routing-job-");
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        return pool;
    }

    @Bean("jobRoutingEngine")
    public RoutingEngine jobRoutingEngine(@Qualifier("routingEngine") RoutingEngine core,
                                         @Value("${app.routing.engine:core}") String selection) {
        if ("stub".equals(selection)) { return new StubRoutingEngine(); }
        if (!"core".equals(selection)) { throw new IllegalArgumentException("app.routing.engine: core или stub"); }
        return core;
    }
}
