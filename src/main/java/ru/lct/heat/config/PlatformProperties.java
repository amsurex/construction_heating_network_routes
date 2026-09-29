package ru.lct.heat.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.jobs")
public class PlatformProperties {
    private int queueCapacity = 50;
    private int estimateBaseSeconds = 5;
    private double estimateSecondsPerPoint = 1.0;

    public long estimateSeconds(int connectionPoints) {
        return Math.max(1, Math.round(estimateBaseSeconds + estimateSecondsPerPoint * connectionPoints));
    }
}
