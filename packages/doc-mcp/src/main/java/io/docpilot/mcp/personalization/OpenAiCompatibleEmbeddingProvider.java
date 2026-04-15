package io.docpilot.mcp.personalization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final URI embeddingsUri;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final int dimensions;

    OpenAiCompatibleEmbeddingProvider(
        ObjectMapper objectMapper,
        HttpClient httpClient,
        String apiUrl,
        String model,
        String apiKey,
        Duration timeout,
        int dimensions
    ) {
        if (apiUrl == null || apiUrl.isBlank()) {
            throw new IllegalStateException("EMBEDDING_API_URL is required when EMBEDDING_PROVIDER=openai-compatible");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("EMBEDDING_MODEL is required when EMBEDDING_PROVIDER=openai-compatible");
        }
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.embeddingsUri = resolveEmbeddingsUri(apiUrl);
        this.model = model;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.timeout = timeout;
        this.dimensions = dimensions;
    }

    @Override
    public String name() {
        return "openai-compatible";
    }

    @Override
    public List<float[]> embed(List<String> inputs) {
        if (inputs.isEmpty()) {
            return List.of();
        }

        try {
            Map<String, Object> requestPayload = new LinkedHashMap<>();
            requestPayload.put("model", model);
            requestPayload.put("input", inputs);
            requestPayload.put("encoding_format", "float");

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(embeddingsUri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestPayload), StandardCharsets.UTF_8));
            if (!apiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Embedding request failed with HTTP " + response.statusCode() + ": " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                throw new IllegalStateException("Embedding response does not contain a data array.");
            }

            float[][] vectors = new float[inputs.size()][];
            for (JsonNode item : data) {
                int index = item.path("index").asInt(-1);
                if (index < 0 || index >= vectors.length) {
                    continue;
                }
                vectors[index] = parseVector(item.path("embedding"));
            }

            List<float[]> orderedVectors = new ArrayList<>(inputs.size());
            for (float[] vector : vectors) {
                if (vector == null) {
                    throw new IllegalStateException("Embedding response is missing one or more vectors.");
                }
                orderedVectors.add(vector);
            }
            return orderedVectors;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize or parse embedding payload.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Embedding request was interrupted.", e);
        }
    }

    private float[] parseVector(JsonNode embeddingNode) {
        if (!embeddingNode.isArray()) {
            throw new IllegalStateException("Embedding payload is not an array.");
        }
        if (embeddingNode.size() != dimensions) {
            throw new IllegalStateException(
                "Embedding dimension mismatch. Expected " + dimensions + " values but received " + embeddingNode.size() + "."
            );
        }

        float[] vector = new float[embeddingNode.size()];
        for (int index = 0; index < embeddingNode.size(); index++) {
            vector[index] = (float) embeddingNode.get(index).asDouble();
        }
        return vector;
    }

    private URI resolveEmbeddingsUri(String apiUrl) {
        String trimmed = apiUrl.strip();
        if (trimmed.endsWith("/embeddings")) {
            return URI.create(trimmed);
        }
        if (!trimmed.endsWith("/")) {
            trimmed = trimmed + "/";
        }
        return URI.create(trimmed + "embeddings");
    }
}