package de.visterion.aletheia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class AletheiaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AletheiaApplication.class, args);
    }
}
