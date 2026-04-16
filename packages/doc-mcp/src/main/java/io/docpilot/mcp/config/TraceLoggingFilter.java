package io.docpilot.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class TraceLoggingFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        String traceId = Optional.ofNullable(request.getHeader("X-DocPilot-Trace-Id"))
            .filter(value -> !value.isBlank())
            .orElseGet(() -> UUID.randomUUID().toString());

        MDC.put("docpilotTraceId", traceId);
        response.setHeader("X-DocPilot-Trace-Id", traceId);

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);
        long startNanos = System.nanoTime();

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            log.info(
                "HTTP trace method={} uri={} query={} status={} durationMs={} requestHeaders={} requestBody={} responseHeaders={} responseBody={}",
                request.getMethod(),
                request.getRequestURI(),
                request.getQueryString(),
                wrappedResponse.getStatus(),
                durationMs,
                sanitizeHeaders(wrappedRequest),
                captureRequestBody(wrappedRequest),
                sanitizeResponseHeaders(wrappedResponse),
                captureResponseBody(wrappedResponse)
            );

            wrappedResponse.copyBodyToResponse();
            MDC.remove("docpilotTraceId");
        }
    }

    private Map<String, String> sanitizeHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        request.getHeaderNames().asIterator().forEachRemaining(name -> {
            if (isSensitiveHeader(name)) {
                headers.put(name, "<redacted>");
            } else {
                headers.put(name, request.getHeader(name));
            }
        });
        return headers;
    }

    private Map<String, String> sanitizeResponseHeaders(HttpServletResponse response) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : response.getHeaderNames()) {
            if (isSensitiveHeader(name)) {
                headers.put(name, "<redacted>");
            } else {
                headers.put(name, response.getHeader(name));
            }
        }
        return headers;
    }

    private boolean isSensitiveHeader(String name) {
        String lowered = name.toLowerCase();
        return lowered.equals("authorization")
            || lowered.equals("cookie")
            || lowered.equals("set-cookie")
            || lowered.equals("x-api-key")
            || lowered.equals("api-key");
    }

    private Object captureRequestBody(ContentCachingRequestWrapper request) throws IOException {
        byte[] body = request.getContentAsByteArray();
        if (body.length == 0 && request.getContentLengthLong() > 0) {
            body = StreamUtils.copyToByteArray(request.getInputStream());
        }
        return decodeBody(body, request.getContentType());
    }

    private Object captureResponseBody(ContentCachingResponseWrapper response) {
        return decodeBody(response.getContentAsByteArray(), response.getContentType());
    }

    private Object decodeBody(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return "";
        }

        String normalizedContentType = contentType == null ? "" : contentType.toLowerCase();
        if (normalizedContentType.startsWith(MediaType.MULTIPART_FORM_DATA_VALUE)) {
            return Map.of(
                "type", "multipart",
                "length", body.length
            );
        }

        if (normalizedContentType.contains(MediaType.APPLICATION_JSON_VALUE)
            || normalizedContentType.startsWith(MediaType.TEXT_PLAIN_VALUE)
            || normalizedContentType.startsWith(MediaType.TEXT_HTML_VALUE)
            || normalizedContentType.startsWith("text/")) {
            Charset charset = StandardCharsets.UTF_8;
            try {
                if (contentType != null) {
                    charset = MediaType.parseMediaType(contentType).getCharset() != null
                        ? MediaType.parseMediaType(contentType).getCharset()
                        : StandardCharsets.UTF_8;
                }
            } catch (Exception ignored) {
                charset = StandardCharsets.UTF_8;
            }

            String text = new String(body, charset);
            try {
                return objectMapper.readTree(text);
            } catch (Exception ignored) {
                return text;
            }
        }

        return Map.of(
            "type", "binary",
            "length", body.length,
            "contentType", contentType == null ? "" : contentType
        );
    }
}