package com.visitorbridge;

import com.visitorbridge.config.NuveqProperties;
import com.visitorbridge.config.VmsProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({NuveqProperties.class, VmsProperties.class})
public class VisitorMiddlewareApplication {

    public static void main(String[] args) {
        SpringApplication.run(VisitorMiddlewareApplication.class, args);
    }
}
