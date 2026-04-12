package io.docpilot.processor;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "docpilot.storage.upload-dir=${java.io.tmpdir}/docpilot-test/uploads",
    "docpilot.storage.registry-dir=${java.io.tmpdir}/docpilot-test/registries",
})
class DocProcessorApplicationTests {

    @Test
    void contextLoads() {
        // Verify the Spring context starts without errors
    }
}
