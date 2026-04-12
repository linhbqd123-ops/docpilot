package io.docpilot.processor.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class AppConfig {

    private final AppProperties props;

    public AppConfig(AppProperties props) {
        this.props = props;
        initDirectories();
    }

    /** Ensure storage directories exist at startup. */
    private void initDirectories() {
        try {
            Files.createDirectories(props.storage().uploadPath());
            Files.createDirectories(props.storage().registryPath());
        } catch (Exception e) {
            throw new IllegalStateException(
                "Cannot create storage directories — check 'docpilot.storage' config", e);
        }
    }

    @Bean
    public MultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver();
    }

    @Bean
    public OpenAPI openAPI() {
        return new OpenAPI().info(new Info()
            .title("DocPilot Doc Processor API")
            .version("1.0")
            .description("""
                High-fidelity DOCX / PDF processing service for DocPilot.
                
                Provides round-trip DOCX ↔ HTML conversion, style registry extraction,
                document structure / outline extraction, and PDF text extraction.
                """)
            .contact(new Contact().name("DocPilot").url("https://github.com/your-org/docpilot"))
            .license(new License().name("MIT").url("https://opensource.org/licenses/MIT"))
        );
    }
}
