package com.sharif899.prodbackend.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/** Cross-cutting beans: configuration properties and the clock. */
@Configuration
@EnableConfigurationProperties(AppProperties.class)
public class AppConfig {

    /** The clock, as a bean so tests can substitute a fixed one. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
