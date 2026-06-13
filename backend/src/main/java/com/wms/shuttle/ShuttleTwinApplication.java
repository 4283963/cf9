package com.wms.shuttle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ShuttleTwinApplication {
    public static void main(String[] args) {
        SpringApplication.run(ShuttleTwinApplication.class, args);
    }
}
