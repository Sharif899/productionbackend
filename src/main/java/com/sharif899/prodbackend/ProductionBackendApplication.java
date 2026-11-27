package com.sharif899.prodbackend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableCaching // without this, @Cacheable on the services is ignored
public class ProductionBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductionBackendApplication.class, args);
    }
}
