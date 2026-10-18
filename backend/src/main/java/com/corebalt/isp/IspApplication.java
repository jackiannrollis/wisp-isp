package com.corebalt.isp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IspApplication {

    public static void main(final String[] args) {
        SpringApplication.run(IspApplication.class, args);
    }
}
