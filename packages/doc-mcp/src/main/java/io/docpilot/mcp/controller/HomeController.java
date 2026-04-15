package io.docpilot.mcp.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Home endpoint returning basic service information (name, version, description,
 * uptime, start time, active profiles and JVM info).
 */
@RestController
@RequestMapping("/")
@RequiredArgsConstructor
public class HomeController {

    private final Environment env;
    private final ApplicationContext ctx;

    // Handle browser requests for favicon.ico to avoid noisy NoResourceFoundException logs
    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        // Return 204 No Content — browsers will not show an icon but the request is satisfied
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public ResponseEntity<?> home(HttpServletRequest request) {
        long startMillis = ctx.getStartupDate();
        long now = System.currentTimeMillis();
        long uptimeMs = Math.max(0, now - startMillis);

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", env.getProperty("info.app.name", "doc-mcp"));
        info.put("version", env.getProperty("info.app.version", "unknown"));
        info.put("description", env.getProperty("info.app.description", ""));
        info.put("startTime", Instant.ofEpochMilli(startMillis).toString());
        info.put("uptimeMs", uptimeMs);
        info.put("activeProfiles", env.getActiveProfiles());
        info.put("serverPort", env.getProperty("server.port", "8080"));
        // Use relative paths so links work correctly behind proxies and can be opened
        // in a new tab from the browser. Example: "/swagger-ui/index.html" and "/api-docs"
        String swaggerPath = "/swagger-ui/index.html";
        info.put("swaggerUi", swaggerPath);
        info.put("javaVersion", System.getProperty("java.version"));

        // If client accepts HTML, render a tiny HTML page with clickable links that open in a new tab
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("text/html")) {
                String html = "<!doctype html>" +
                    "<html><head><meta charset=\"utf-8\"><title>Doc MCP</title></head><body>" +
                    "<h1>Doc MCP</h1>" +
                    String.format("<p><strong>%s</strong> — %s (v%s)</p>",
                        env.getProperty("info.app.name", "doc-mcp"),
                            env.getProperty("info.app.description", ""),
                            env.getProperty("info.app.version", "unknown")) +
                    String.format("<p>Started: %s — Uptime: %d ms</p>", Instant.ofEpochMilli(startMillis).toString(), uptimeMs) +
                    String.format("<p><a href=\"%s\" target=\"_blank\" rel=\"noopener noreferrer\">Open Swagger UI</a></p>", swaggerPath) +
                    "</body></html>";

            return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
        }

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(info);
    }
}
