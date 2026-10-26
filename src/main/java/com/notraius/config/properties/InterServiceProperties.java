package com.notraius.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.inter-service")
@Getter
@Setter
public class InterServiceProperties {

    private Timeout pay = new Timeout();
    

    @Getter
    @Setter
    public static class Timeout {
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 10000;
    }
}
