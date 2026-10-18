package com.itajay.superassistant.app;

import com.itajay.superassistant.rag.RagService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Route contract for the knowledge upload endpoint.
 *
 * <p>This test exists because the mapping used to be {@code /knowledge/upload} while
 * the frontend called {@code /api/knowledge/upload}: every upload was a 404. It also
 * pins the source name, which must be the user's original file name rather than the
 * random temp file used for parsing.</p>
 */
class RagControllerTest {

    private final RagService ragService = mock(RagService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new RagController(ragService))
            .build();

    @Test
    void uploadIsMappedUnderApiAndUsesTheOriginalFileName() throws Exception {
        when(ragService.addSource(any(Resource.class), eq("paper.pdf"))).thenReturn(3);

        mvc.perform(multipart("/api/knowledge/upload")
                        .file(new MockMultipartFile(
                                "file", "paper.pdf", "application/pdf", "pdf-bytes".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.chunks").value(3))
                .andExpect(jsonPath("$.filename").value("paper.pdf"));

        verify(ragService).addSource(any(Resource.class), eq("paper.pdf"));
    }

    @Test
    void clientSuppliedDirectoriesAreStrippedFromTheSourceName() throws Exception {
        when(ragService.addSource(any(Resource.class), eq("paper.pdf"))).thenReturn(1);

        mvc.perform(multipart("/api/knowledge/upload")
                        .file(new MockMultipartFile(
                                "file", "..\\..\\papers\\paper.pdf", "application/pdf", "x".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filename").value("paper.pdf"));

        verify(ragService).addSource(any(Resource.class), eq("paper.pdf"));
    }

    @Test
    void noExtractedChunksIsReportedAsFailure() throws Exception {
        when(ragService.addSource(any(Resource.class), eq("empty.txt"))).thenReturn(0);

        mvc.perform(multipart("/api/knowledge/upload")
                        .file(new MockMultipartFile("file", "empty.txt", "text/plain", "text".getBytes())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }
}
