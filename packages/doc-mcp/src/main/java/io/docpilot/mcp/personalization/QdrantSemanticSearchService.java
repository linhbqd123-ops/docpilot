package io.docpilot.mcp.personalization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.config.AppProperties;
import io.docpilot.mcp.model.session.DocumentSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "docpilot.personalization", name = "provider", havingValue = "qdrant")
@Slf4j
public class QdrantSemanticSearchService implements SemanticSearchService, InitializingBean {

    private static final int UPSERT_BATCH_SIZE = 64;

    private final AppProperties props;
    private final ObjectMapper objectMapper;
    private final DocumentSemanticChunkExtractor chunkExtractor;
    private final HttpClient httpClient;
    private final URI qdrantBaseUri;
    private final EmbeddingProvider embeddingProvider;

    public QdrantSemanticSearchService(
        AppProperties props,
        ObjectMapper objectMapper,
        DocumentSemanticChunkExtractor chunkExtractor
    ) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.chunkExtractor = chunkExtractor;
        Duration timeout = Duration.ofMillis(props.personalization().requestTimeoutMs());
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .build();
        this.qdrantBaseUri = normaliseBaseUri(props.personalization().qdrantUrl());
        this.embeddingProvider = createEmbeddingProvider(timeout);
    }

    @Override
    public void afterPropertiesSet() {
        ensureCollectionExists();
        log.info(
            "Qdrant semantic retrieval enabled: collection={} embeddingProvider={}",
            props.personalization().qdrantCollection(),
            embeddingProvider.name()
        );
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String providerName() {
        return "qdrant/" + embeddingProvider.name();
    }

    @Override
    public void reindexSession(DocumentSession session) {
        try {
            reindexSessionUnsafe(session);
        } catch (RuntimeException e) {
            log.warn("Failed to reindex session {} in Qdrant: {}", session.getSessionId(), e.getMessage());
        }
    }

    @Override
    public List<SemanticSearchMatch> search(DocumentSession session, String query, int limit) {
        try {
            return searchUnsafe(session, query, limit);
        } catch (RuntimeException e) {
            log.warn("Qdrant search failed for session {}: {}", session.getSessionId(), e.getMessage());
            return List.of();
        }
    }

    private void reindexSessionUnsafe(DocumentSession session) {
        if (session == null || session.getSessionId() == null || session.getSessionId().isBlank()) {
            return;
        }

        List<DocumentSemanticChunk> chunks = chunkExtractor.extract(session);
        deleteSessionPoints(session.getSessionId());
        if (chunks.isEmpty()) {
            return;
        }

        List<float[]> vectors = embeddingProvider.embed(chunks.stream().map(DocumentSemanticChunk::indexedText).toList());
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException("Embedding provider returned " + vectors.size() + " vectors for " + chunks.size() + " chunks.");
        }

        List<Map<String, Object>> points = new ArrayList<>();
        for (int index = 0; index < chunks.size(); index++) {
            DocumentSemanticChunk chunk = chunks.get(index);
            points.add(Map.of(
                "id", chunk.pointId(),
                "vector", vectors.get(index),
                "payload", payloadForChunk(session, chunk)
            ));

            if (points.size() >= UPSERT_BATCH_SIZE) {
                upsertPoints(points);
                points.clear();
            }
        }

        if (!points.isEmpty()) {
            upsertPoints(points);
        }
    }

    private List<SemanticSearchMatch> searchUnsafe(DocumentSession session, String query, int limit) {
        String normalisedQuery = DocumentTextSupport.normaliseWhitespace(query);
        if (normalisedQuery.isBlank()) {
            return List.of();
        }

        int effectiveLimit = Math.max(1, Math.min(limit <= 0 ? props.personalization().maxSearchResults() : limit, props.personalization().maxSearchResults()));
        float[] vector = embeddingProvider.embed(List.of(normalisedQuery)).get(0);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("query", vector);
        requestBody.put("filter", sessionFilter(session.getSessionId()));
        requestBody.put("limit", effectiveLimit);
        requestBody.put("with_payload", true);

        JsonNode response = sendJson("POST", collectionPath("/points/query"), requestBody, Set.of(200));
        JsonNode points = response.path("result").path("points");
        if (!points.isArray()) {
            return List.of();
        }

        List<SemanticSearchMatch> matches = new ArrayList<>();
        for (JsonNode point : points) {
            JsonNode payload = point.path("payload");
            String blockId = payload.path("block_id").asText(null);
            if (blockId == null || blockId.isBlank()) {
                continue;
            }
            matches.add(new SemanticSearchMatch(
                blockId,
                payload.path("type").asText("UNKNOWN"),
                payload.path("text").asText(""),
                payload.path("logical_path").asText(""),
                payload.path("heading_path").asText(""),
                point.path("score").asDouble(0.0d)
            ));
        }
        return matches;
    }

    private EmbeddingProvider createEmbeddingProvider(Duration timeout) {
        String configuredProvider = props.personalization().embeddingProvider();
        String provider = configuredProvider == null ? "" : configuredProvider.strip().toLowerCase();
        if (provider.isBlank() || provider.equals("hash") || provider.equals("hashing") || provider.equals("local-hash")) {
            return new HashingEmbeddingProvider(props.personalization().embeddingDimensions());
        }
        if (provider.equals("openai-compatible")) {
            return new OpenAiCompatibleEmbeddingProvider(
                objectMapper,
                httpClient,
                props.personalization().embeddingApiUrl(),
                props.personalization().embeddingModel(),
                props.personalization().embeddingApiKey(),
                timeout,
                props.personalization().embeddingDimensions()
            );
        }
        throw new IllegalStateException("Unsupported EMBEDDING_PROVIDER: " + configuredProvider);
    }

    private void ensureCollectionExists() {
        HttpResponse<String> response = sendRequest("GET", collectionPath("/exists"), null, Set.of(200, 404));
        if (response.statusCode() == 404) {
            createCollection();
            return;
        }

        try {
            JsonNode body = objectMapper.readTree(response.body());
            if (!body.path("result").path("exists").asBoolean(false)) {
                createCollection();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse Qdrant collection existence response.", e);
        }
    }

    private void createCollection() {
        Map<String, Object> vectorConfig = new LinkedHashMap<>();
        vectorConfig.put("size", props.personalization().embeddingDimensions());
        vectorConfig.put("distance", "Cosine");
        vectorConfig.put("on_disk", true);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("vectors", vectorConfig);
        requestBody.put("on_disk_payload", true);

        sendJson("PUT", collectionPath(""), requestBody, Set.of(200));
    }

    private void deleteSessionPoints(String sessionId) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("filter", sessionFilter(sessionId));
        sendJson("POST", collectionPath("/points/delete?wait=true"), requestBody, Set.of(200));
    }

    private void upsertPoints(List<Map<String, Object>> points) {
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("points", points);
        sendJson("PUT", collectionPath("/points?wait=true"), requestBody, Set.of(200));
    }

    private Map<String, Object> payloadForChunk(DocumentSession session, DocumentSemanticChunk chunk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_id", chunk.sessionId());
        payload.put("block_id", chunk.blockId());
        payload.put("type", chunk.type());
        payload.put("logical_path", chunk.logicalPath());
        payload.put("heading_path", chunk.headingPath());
        payload.put("text", chunk.text());
        payload.put("filename", session.getFilename());
        payload.put("current_revision_id", session.getCurrentRevisionId());
        return payload;
    }

    private Map<String, Object> sessionFilter(String sessionId) {
        return Map.of(
            "must",
            List.of(Map.of(
                "key", "session_id",
                "match", Map.of("value", sessionId)
            ))
        );
    }

    private JsonNode sendJson(String method, String path, Object body, Set<Integer> expectedStatuses) {
        HttpResponse<String> response = sendRequest(method, path, body, expectedStatuses);
        try {
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse Qdrant response body.", e);
        }
    }

    private HttpResponse<String> sendRequest(String method, String path, Object body, Set<Integer> expectedStatuses) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(qdrantBaseUri.resolve(path))
                .timeout(Duration.ofMillis(props.personalization().requestTimeoutMs()));
            if (body == null) {
                requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                requestBuilder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8));
            }
            if (props.personalization().qdrantApiKey() != null && !props.personalization().qdrantApiKey().isBlank()) {
                requestBuilder.header("api-key", props.personalization().qdrantApiKey().strip());
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (!expectedStatuses.contains(response.statusCode())) {
                throw new IllegalStateException(
                    "Qdrant request failed for " + path + " with HTTP " + response.statusCode() + ": " + response.body()
                );
            }
            return response;
        } catch (IOException e) {
            throw new IllegalStateException("Qdrant request failed for " + path + ".", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Qdrant request was interrupted for " + path + ".", e);
        }
    }

    private URI normaliseBaseUri(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.strip();
        if (trimmed.isBlank()) {
            throw new IllegalStateException("QDRANT_URL must not be blank when PERSONALIZATION_PROVIDER=qdrant");
        }
        if (!trimmed.endsWith("/")) {
            trimmed = trimmed + "/";
        }
        return URI.create(trimmed);
    }

    private String collectionPath(String suffix) {
        String encodedCollection = URLEncoder.encode(props.personalization().qdrantCollection(), StandardCharsets.UTF_8).replace("+", "%20");
        return "collections/" + encodedCollection + suffix;
    }
}