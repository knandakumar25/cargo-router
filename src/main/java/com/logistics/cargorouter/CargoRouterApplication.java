package com.logistics.cargorouter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;

/**
 * Entry point for the Cargo Router microservice.
 *
 * @EnableScheduling activates AgenticLoop's @Scheduled cycle.
 * RestTemplate bean mirrors JPMC MidasCoreApplication — the same pattern
 * used there for IncentiveService is used here for WeatherMonitor.
 */
@SpringBootApplication
@EnableScheduling
public class CargoRouterApplication {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    public static void main(String[] args) {
        SpringApplication.run(CargoRouterApplication.class, args);
    }
}
