package com.itajay.superassistant.tool;

import com.itajay.superassistant.config.PaperDownloadProperties;
import com.itajay.superassistant.config.PaperDownloadPropertiesConfig;
import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the paper-download tool wires up and that {@code agent.paper.*} binds.
 *
 * <p>Uses {@link ApplicationContextRunner} rather than {@code @SpringBootTest}: the full
 * application context requires the MCP email server on :8081 and a live database, neither
 * of which is available in a unit test. This exercises exactly the beans under test.</p>
 */
class PaperDownloadWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(PaperDownloadPropertiesConfig.class,
                    PaperDownloadTool.class, PaperTextTool.class);

    @Test
    void toolAndPropertiesAreRegistered() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(PaperDownloadTool.class);
            assertThat(context).hasSingleBean(PaperTextTool.class);
            assertThat(context).hasSingleBean(PaperDownloadProperties.class);
        });
    }

    @Test
    void propertiesBindFromExternalConfiguration() {
        runner.withPropertyValues(
                        "agent.paper.investigation-dir=my-research",
                        "agent.paper.max-pdf-bytes=1234567",
                        "agent.paper.min-pdf-bytes=4096",
                        "agent.paper.max-text-chars=5000",
                        "agent.paper.max-pages=12",
                        "agent.paper.connect-timeout-ms=1000",
                        "agent.paper.request-timeout-ms=2000",
                        "agent.paper.max-retries=7",
                        "agent.paper.initial-backoff-ms=100",
                        "agent.paper.max-backoff-ms=900")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PaperDownloadProperties props = context.getBean(PaperDownloadProperties.class);
                    assertThat(props.getInvestigationDir()).isEqualTo("my-research");
                    assertThat(props.getMaxPdfBytes()).isEqualTo(1_234_567L);
                    assertThat(props.getMinPdfBytes()).isEqualTo(4_096L);
                    assertThat(props.getMaxTextChars()).isEqualTo(5_000);
                    assertThat(props.getMaxPages()).isEqualTo(12);
                    assertThat(props.getConnectTimeoutMs()).isEqualTo(1_000);
                    assertThat(props.getRequestTimeoutMs()).isEqualTo(2_000);
                    assertThat(props.getMaxRetries()).isEqualTo(7);
                    assertThat(props.getInitialBackoffMs()).isEqualTo(100L);
                    assertThat(props.getMaxBackoffMs()).isEqualTo(900L);
                    assertThat(props.getHostFallbacks()).isEmpty();
                });
    }

    @Test
    void hostFallbacksBindAsAMap() {
        runner.withPropertyValues(
                        "agent.paper.host-fallbacks.arxiv.org[0]=https://export.arxiv.org",
                        "agent.paper.host-fallbacks.arxiv.org[1]=https://mirror.example")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PaperDownloadProperties props = context.getBean(PaperDownloadProperties.class);
                    assertThat(props.getHostFallbacks()).containsKey("arxiv.org");
                    assertThat(props.getHostFallbacks().get("arxiv.org"))
                            .containsExactly("https://export.arxiv.org", "https://mirror.example");
                });
    }

    @Test
    void hostFallbackSwapsTheOriginAndKeepsPathAndQuery() {
        runner.withPropertyValues(
                        "agent.paper.host-fallbacks.arxiv.org[0]=https://export.arxiv.org")
                .run(context -> {
                    PaperDownloadTool tool = context.getBean(PaperDownloadTool.class);
                    List<String> urls = tool.candidateUrls("https://arxiv.org/pdf/1706.03762?v=2");

                    assertThat(urls).contains("https://export.arxiv.org/pdf/1706.03762?v=2");
                    // The original must still be tried first — the mirror is a fallback.
                    assertThat(urls.get(0)).isEqualTo("https://arxiv.org/pdf/1706.03762");
                    assertThat(urls.indexOf("https://arxiv.org/pdf/1706.03762"))
                            .isLessThan(urls.indexOf("https://export.arxiv.org/pdf/1706.03762?v=2"));
                });
    }

    @Test
    void hostFallbackWithPlaceholderSubstitutesThePath() {
        runner.withPropertyValues(
                        "agent.paper.host-fallbacks.arxiv.org[0]=https://mirror.example/m?u=%s")
                .run(context -> {
                    PaperDownloadTool tool = context.getBean(PaperDownloadTool.class);
                    List<String> urls = tool.candidateUrls("https://arxiv.org/pdf/1706.03762");

                    assertThat(urls).contains("https://mirror.example/m?u=pdf/1706.03762");
                    // The whole URL must never be substituted into the path.
                    assertThat(urls).noneMatch(u -> u.contains("mirror.example/m?u=https"));
                });
    }

    @Test
    void noFallbackConfiguredLeavesCandidatesUnchanged() {
        runner.run(context -> {
            PaperDownloadTool tool = context.getBean(PaperDownloadTool.class);
            assertThat(tool.candidateUrls("https://example.com/paper.pdf"))
                    .containsExactly("https://example.com/paper.pdf");
        });
    }

    @Test
    void exposesOnlyDownloadToTheResearchAgent() {
        // Close reading moved to the writer agent. This agent must not be able to
        // read a paper's body, so the boundary has to hold in the tool list itself.
        List<String> names = Arrays.stream(PaperDownloadTool.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .sorted()
                .toList();
        assertThat(names).containsExactly("downloadPaper", "listDownloadedPapers");
    }

    @Test
    void everyPaperToolTakesTheTopicAsItsFirstParameter() {
        // The topic is what routes a paper into investigation/{topic}/papers/. A tool
        // that forgot it would write to the wrong place or fail at runtime.
        for (Method method : PaperDownloadTool.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            assertThat(method.getParameterCount())
                    .as("%s should take a topic first", method.getName())
                    .isGreaterThan(0);
            assertThat(method.getParameterTypes()[0])
                    .as("%s first parameter should be the topic", method.getName())
                    .isEqualTo(String.class);
            assertThat(method.getParameters()[0].getName())
                    .as("%s first parameter should be named topic", method.getName())
                    .isEqualTo("topic");
        }
    }

    @Test
    void reportsNoPapersForAnUnusedTopic() {
        runner.run(context -> {
            PaperTextTool tool = context.getBean(PaperTextTool.class);
            String listing = tool.listDownloadedPapers("a-topic-with-no-papers");
            assertThat(listing).doesNotStartWith("Error");
            assertThat(listing).contains("No papers downloaded yet");
        });
    }

    @Test
    void bothToolsReportTheSameListing() {
        // The two list tools share one implementation; a divergence would mean the
        // research agent and the writer agent disagree about what exists on disk.
        runner.run(context -> {
            String fromDownloader = context.getBean(PaperDownloadTool.class)
                    .listDownloadedPapers("a-topic-with-no-papers");
            String fromReader = context.getBean(PaperTextTool.class)
                    .listDownloadedPapers("a-topic-with-no-papers");
            assertThat(fromDownloader).isEqualTo(fromReader);
        });
    }

    @Test
    void exposesOnlyReadingToTheWriterAgent() {
        // The mirror of the test above: the writer can read papers and list them, but
        // must not be able to download — that is the research agent's job, and a second
        // downloader would bypass the relevance gate.
        List<String> names = Arrays.stream(PaperTextTool.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(Method::getName)
                .sorted()
                .toList();
        assertThat(names).containsExactly("extractPaperText", "listDownloadedPapers");
    }

    @Test
    void paperTextToolsAlsoTakeTheTopicFirst() {
        for (Method method : PaperTextTool.class.getDeclaredMethods()) {
            if (!method.isAnnotationPresent(Tool.class)) {
                continue;
            }
            assertThat(method.getParameterTypes()[0])
                    .as("%s first parameter should be the topic", method.getName())
                    .isEqualTo(String.class);
            assertThat(method.getParameters()[0].getName())
                    .as("%s first parameter should be named topic", method.getName())
                    .isEqualTo("topic");
        }
    }

    @Test
    void resolvedPapersDirectoryMatchesTheDocumentedLayout() {
        runner.run(context -> assertThat(WorkspacePaths.relative(WorkspacePaths.papersDir("多主体布局控制")))
                .isEqualTo("investigation/多主体布局控制/papers"));
    }
}
