package com.kasa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KasaApplication {

    public static void main(String[] args) {
        SpringApplication.run(KasaApplication.class, args);
    }
}
