package com.example.homegaibkrponte;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class HomegaIbkrponteApplication {

    public static void main(String[] args) {
        // A lógica de conexão agora é gerenciada pelo ConnectionManagerService via @PostConstruct
        SpringApplication.run(HomegaIbkrponteApplication.class, args);
    }
}
