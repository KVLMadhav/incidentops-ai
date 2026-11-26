package com.incidentops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@org.springframework.scheduling.annotation.EnableScheduling
public class IncidentOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(IncidentOpsApplication.class, args);
    }
}
