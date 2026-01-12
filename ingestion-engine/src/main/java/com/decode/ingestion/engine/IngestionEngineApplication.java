package com.decode.ingestion.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ComponentScan(basePackages = "com.decode")
@EntityScan(basePackages = "com.decode")
@EnableJpaRepositories(basePackages = "com.decode")
@EnableAsync
public class IngestionEngineApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionEngineApplication.class, args);
    }

}
