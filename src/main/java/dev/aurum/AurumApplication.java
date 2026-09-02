package dev.aurum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AurumApplication {

    public static void main(String[] args) {
        SpringApplication.run(AurumApplication.class, args);
    }
}
