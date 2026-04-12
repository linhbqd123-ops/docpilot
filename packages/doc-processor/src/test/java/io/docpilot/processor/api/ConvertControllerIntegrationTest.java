package io.docpilot.processor.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the REST API layer.
 * Uses MockMvc to verify routing, response shapes, and error handling
 * without starting a real HTTP server.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "docpilot.storage.upload-dir=${java.io.tmpdir}/docpilot-test/uploads",
    "docpilot.storage.registry-dir=${java.io.tmpdir}/docpilot-test/registries",
})
class ConvertControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void getStyles_unknownDocId_returns404() throws Exception {
        mockMvc.perform(get("/api/styles/nonexistent-doc-id"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("Not Found"));
    }

    @Test
    void getStructure_unknownDocId_returns404() throws Exception {
        mockMvc.perform(get("/api/structure/nonexistent-doc-id"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void actuatorHealth_returns200() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void swaggerUi_isAccessible() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
            .andExpect(status().isOk());
    }
}
