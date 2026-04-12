package io.docpilot.processor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DocProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocProcessorApplication.class, args);
    }
}
