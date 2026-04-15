package io.docpilot.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.docpilot.mcp.config.AppProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class DocMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(DocMcpApplication.class, args);
    }
}
