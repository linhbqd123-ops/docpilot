package io.docpilot.mcp.personalization;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.docpilot.mcp.config.AppConfig;
import io.docpilot.mcp.config.AppProperties;
import io.docpilot.mcp.model.document.Anchor;
import io.docpilot.mcp.model.document.ComponentType;
import io.docpilot.mcp.model.document.ContentProps;
import io.docpilot.mcp.model.document.DocumentComponent;
import io.docpilot.mcp.model.document.LayoutProps;
import io.docpilot.mcp.model.session.DocumentSession;
import io.docpilot.mcp.model.session.SessionState;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QdrantSemanticSearchServiceTest {

    @Test
    void createsCollectionIndexesSessionAndQueriesViaPointsQuery() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(200, """
                {"status":"ok","result":{"exists":false}}
                """));
            server.enqueue(json(200, """
                {"status":"ok","result":true}
                """));
            server.enqueue(json(200, """
                {"status":"ok","result":{"operation_id":1}}
                """));
            server.enqueue(json(200, """
                {"status":"ok","result":{"operation_id":2}}
                """));
            server.enqueue(json(200, """
                {
                  "status":"ok",
                  "result": {
                    "points": [
                      {
                        "id": "session-1__paragraph-1",
                        "score": 0.92,
                        "payload": {
                          "block_id": "paragraph-1",
                          "type": "PARAGRAPH",
                          "text": "The customer may terminate with 30 days notice.",
                          "logical_path": "section[1]/paragraph[1]",
                          "heading_path": "Termination"
                        }
                      }
                    ]
                  }
                }
                """));
            server.start();

            AppProperties props = props(server);
            ObjectMapper objectMapper = new AppConfig().objectMapper();
            QdrantSemanticSearchService service = new QdrantSemanticSearchService(
                props,
                objectMapper,
                new DocumentSemanticChunkExtractor(props)
            );

            service.afterPropertiesSet();
            service.reindexSession(session());
            List<SemanticSearchMatch> matches = service.search(session(), "termination notice", 5);

            assertEquals(1, matches.size());
            assertEquals("paragraph-1", matches.get(0).blockId());
            assertEquals("Termination", matches.get(0).headingPath());

            RecordedRequest existsRequest = server.takeRequest();
            assertEquals("GET", existsRequest.getMethod());
            assertTrue(existsRequest.getPath().endsWith("/collections/docpilot_personalization/exists"));

            RecordedRequest createRequest = server.takeRequest();
            assertEquals("PUT", createRequest.getMethod());
            JsonNode createPayload = objectMapper.readTree(createRequest.getBody().readUtf8());
            assertEquals(1536, createPayload.path("vectors").path("size").asInt());

            RecordedRequest deleteRequest = server.takeRequest();
            assertEquals("POST", deleteRequest.getMethod());
            JsonNode deletePayload = objectMapper.readTree(deleteRequest.getBody().readUtf8());
            assertEquals("session-1", deletePayload.path("filter").path("must").get(0).path("match").path("value").asText());

            RecordedRequest upsertRequest = server.takeRequest();
            assertEquals("PUT", upsertRequest.getMethod());
            JsonNode upsertPayload = objectMapper.readTree(upsertRequest.getBody().readUtf8());
            assertEquals(2, upsertPayload.path("points").size());
            assertEquals("section[1]/paragraph[1]", upsertPayload.path("points").get(1).path("payload").path("logical_path").asText());
            assertNotNull(upsertPayload.path("points").get(0).path("vector"));

            RecordedRequest queryRequest = server.takeRequest();
            assertEquals("POST", queryRequest.getMethod());
            assertTrue(queryRequest.getPath().endsWith("/collections/docpilot_personalization/points/query"));
            JsonNode queryPayload = objectMapper.readTree(queryRequest.getBody().readUtf8());
            assertEquals(5, queryPayload.path("limit").asInt());
            assertEquals("session-1", queryPayload.path("filter").path("must").get(0).path("match").path("value").asText());
        }
    }

    private static MockResponse json(int statusCode, String body) {
        return new MockResponse()
            .setResponseCode(statusCode)
            .addHeader("Content-Type", "application/json")
            .setBody(body);
    }

    private static AppProperties props(MockWebServer server) {
        return new AppProperties(
            new AppProperties.Storage(
                "./data",
                "./data/uploads",
                "./data/registries",
                "./data/sessions",
                "./data/revisions",
                "./data/legacy-archive"
            ),
            new AppProperties.Persistence(
                "./data/sqlite/doc-mcp.db",
                2,
                5000,
                32L * 1024 * 1024,
                8192,
                1000,
                16L * 1024 * 1024,
                false,
                false,
                false
            ),
            new AppProperties.Personalization(
                "qdrant",
                server.url("/").toString(),
                "docpilot_personalization",
                1536,
                "",
                "hashing",
                "",
                "",
                "",
                15000,
                8,
                1200
            ),
            new AppProperties.Processing(104857600L, true),
            new AppProperties.Mcp("doc-mcp", "2.0.0", "2024-11-05")
        );
    }

    private static DocumentSession session() {
        DocumentComponent heading = DocumentComponent.builder()
            .id("heading-1")
            .type(ComponentType.HEADING)
            .layoutProps(LayoutProps.builder().headingLevel(1).build())
            .contentProps(ContentProps.builder().text("Termination").build())
            .anchor(Anchor.builder().logicalPath("section[1]/heading[1]").build())
            .build();

        DocumentComponent paragraph = DocumentComponent.builder()
            .id("paragraph-1")
            .type(ComponentType.PARAGRAPH)
            .contentProps(ContentProps.builder().text("The customer may terminate with 30 days notice.").build())
            .anchor(Anchor.builder().logicalPath("section[1]/paragraph[1]").build())
            .build();

        DocumentComponent root = DocumentComponent.builder()
            .id("root")
            .type(ComponentType.DOCUMENT)
            .children(List.of(heading, paragraph))
            .build();

        return DocumentSession.builder()
            .sessionId("session-1")
            .docId("doc-1")
            .filename("contract.docx")
            .originalFilename("contract.docx")
            .root(root)
            .currentRevisionId("rev-current")
            .state(SessionState.READY)
            .createdAt(Instant.parse("2024-01-01T00:00:00Z"))
            .lastModifiedAt(Instant.parse("2024-01-01T00:00:00Z"))
            .wordCount(120)
            .paragraphCount(2)
            .tableCount(0)
            .imageCount(0)
            .sectionCount(1)
            .build();
    }
}