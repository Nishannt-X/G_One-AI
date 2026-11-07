package com.example.g_one;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GOneApplication {

    public static void main(String[] args) {
        SpringApplication.run(GOneApplication.class, args);
    }
}
