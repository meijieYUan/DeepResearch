package com.itajay.superassistant.tool;

import com.itajay.superassistant.workspace.WorkspacePaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the per-paper analysis file rules.
 *
 * <p>Two properties matter. The security-relevant one is that a model-supplied short
 * name or path can never reach a file outside {@code investigation/{topic}/analysis/}
 * — these files are written by an agent that a reviewer then reads back, so a path
 * escape would let one tool write over another topic's work. The correctness-relevant
 * one is that the short name maps to the same file on write and on read: the writer
 * asks for a paper by the name the analyst stored it under, and if the mapping is not
 * a pure function of the name the document's relative links break.</p>
 */
class AnalysisPathTest {

    private static final String TOPIC = "测试-分析路径-请忽略";

    @TempDir
    Path scratch;

    @AfterEach
    void removeScratchTopic() throws IOException {
        // The listing test has to touch the real layout — analysisRoot() resolves
        // against the project root, not a temp dir — so clean up what it created.
        // Bounded to this one topic directory.
        Path topicDir = WorkspacePaths.topicDir(TOPIC);
        if (!Files.exists(topicDir)) {
            return;
        }
        try (var walk = Files.walk(topicDir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    @Test
    void analysisFilesSitBesidePapersUnderTheTopic() {
        assertThat(WorkspacePaths.relative(AnalysisStore.analysisRoot("多主体布局控制")))
                .isEqualTo("investigation/多主体布局控制/analysis");
    }

    @Test
    void shortNameMapsToTheSameFileThePdfUses() {
        // analysis/MS-Diffusion.md pairs with papers/MS-Diffusion.pdf, which is what
        // makes the two obvious to a human browsing investigation/{topic}/.
        assertThat(AnalysisStore.nameFor("MS-Diffusion")).isEqualTo("MS-Diffusion");
        assertThat(AnalysisStore.nameFor("MS-Diffusion.md")).isEqualTo("MS-Diffusion");
        assertThat(AnalysisStore.nameFor("  MS-Diffusion  ")).isEqualTo("MS-Diffusion");
    }

    @Test
    void shortNameIsSlugifiedSoItCannotCarryASeparator() {
        assertThat(AnalysisStore.nameFor("../../evil")).isEqualTo("evil");
        assertThat(AnalysisStore.nameFor("a/b")).isEqualTo("a-b");
        assertThat(AnalysisStore.nameFor("C:\\windows\\x")).isEqualTo("C-windows-x");
        assertThat(AnalysisStore.nameFor("多主体布局控制")).isEqualTo("多主体布局控制");
    }

    @Test
    void shortNameWithoutUsableCharactersIsRejected() {
        assertThatThrownBy(() -> AnalysisStore.nameFor(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalysisStore.nameFor("   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalysisStore.nameFor(".."))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bareShortNameResolvesInsideTheAnalysisDirectory() {
        Path expected = AnalysisStore.analysisRoot("多主体布局控制").resolve("MS-Diffusion.md");

        assertThat(AnalysisStore.resolveMarkdown("多主体布局控制", "MS-Diffusion")).isEqualTo(expected);
        assertThat(AnalysisStore.resolveMarkdown("多主体布局控制", "MS-Diffusion.md")).isEqualTo(expected);
        assertThat(AnalysisStore.resolveMarkdown("多主体布局控制", "  MS-Diffusion  ")).isEqualTo(expected);
    }

    @Test
    void projectRelativePathIsAcceptedOnlyInsideThisTopicsAnalysisDirectory() {
        assertThat(AnalysisStore.resolveMarkdown("多主体布局控制",
                "investigation/多主体布局控制/analysis/MS-Diffusion.md"))
                .isEqualTo(AnalysisStore.analysisRoot("多主体布局控制").resolve("MS-Diffusion.md"));

        // A perfectly valid project path — just the wrong folder. Following it would
        // let the writer read another topic's (or the papers') files.
        assertThatThrownBy(() -> AnalysisStore.resolveMarkdown("多主体布局控制",
                "investigation/多主体布局控制/papers/MS-Diffusion.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AnalysisStore.resolveMarkdown("多主体布局控制",
                "investigation/另一个课题/analysis/MS-Diffusion.md"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pathsThatEscapeTheWorkspaceAreRejected() {
        for (String hostile : new String[]{
                "../../etc/passwd",
                "investigation/../../x",
                "app/src/main/resources/skills/research_writing_skill/SKILL.md",
                "..",
                "   ",
                null}) {
            assertThatThrownBy(() -> AnalysisStore.resolveMarkdown("多主体布局控制", hostile))
                    .as("input %s must not resolve to a file", hostile)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void thereIsNoAnalysisDirectoryBeforeAnythingHasBeenAnalysed() {
        assertThat(AnalysisStore.markdownFiles(TOPIC)).isEmpty();
        // A hostile topic must degrade to "nothing there" rather than throw: the
        // workflow calls this while assembling the reviewer's input and the report.
        assertThat(AnalysisStore.markdownFiles("..")).isEmpty();
        assertThat(AnalysisStore.markdownFiles(null)).isEmpty();
    }

    @Test
    void listingFindsEveryAnalysisFileAndNothingElse() throws IOException {
        Path dir = AnalysisStore.analysisRoot(TOPIC);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("MS-Diffusion.md"), "# MS-Diffusion\n");
        Files.writeString(dir.resolve("AnyMS.md"), "# AnyMS\n");
        Files.writeString(dir.resolve("notes.txt"), "not an analysis\n");
        Files.createDirectories(dir.resolve("nested"));

        List<Path> found = AnalysisStore.markdownFiles(TOPIC);

        assertThat(found).hasSize(2);
        assertThat(found).extracting(AnalysisStore::shortNameOf)
                .containsExactly("AnyMS", "MS-Diffusion");
    }

    @Test
    void figuresSitUnderTheAnalysisDirectorySoOneTopicIsOneUnit() {
        assertThat(WorkspacePaths.relative(WorkspacePaths.figuresDir("多主体布局控制")))
                .isEqualTo("investigation/多主体布局控制/analysis/figures");
    }

    @Test
    void thereAreNoFiguresBeforeAnythingHasBeenExtracted() {
        assertThat(AnalysisStore.figureFiles(TOPIC)).isEmpty();
        // Same degradation contract as markdownFiles: the workflow calls this while
        // building the reviewer's inventory, so a bad topic must mean "none" rather
        // than an exception that kills the review.
        assertThat(AnalysisStore.figureFiles("..")).isEmpty();
        assertThat(AnalysisStore.figureFiles(null)).isEmpty();
    }

    @Test
    void listingFiguresFindsOnlyPngsDirectlyUnderTheFiguresDirectory() throws IOException {
        Path dir = WorkspacePaths.figuresDir(TOPIC);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("any.txt"), "not a figure\n");
        Files.write(dir.resolve("MS-Diffusion_Fig2.png"), new byte[]{1, 2, 3});
        Files.write(dir.resolve("AnyMS_Fig1.png"), new byte[]{4, 5, 6});
        // Neither of these can be referenced by a document, so listing them would
        // make the reviewer's inventory disagree with what is actually usable.
        Files.createDirectories(dir.resolve("nested"));
        Files.writeString(AnalysisStore.analysisRoot(TOPIC).resolve("MS-Diffusion.md"), "# MS-Diffusion\n");

        List<Path> found = AnalysisStore.figureFiles(TOPIC);

        assertThat(found).extracting(p -> p.getFileName().toString())
                .containsExactly("AnyMS_Fig1.png", "MS-Diffusion_Fig2.png");
    }

    @Test
    void headerMarkersAreReadBackFromDisk() throws IOException {
        Path file = scratch.resolve("MS-Diffusion.md");
        Files.writeString(file, """
                # MS-Diffusion

                > 来源等级：FULL_TEXT
                > 一句话定位：用 bbox mask 约束 attention 作用范围
                > 生成时间：2026-09-18 10:00:00

                ---

                ## 1. 论文概要与作者意图
                ...
                """, StandardCharsets.UTF_8);

        AnalysisStore.AnalysisMeta meta = AnalysisStore.readMeta(file);

        assertThat(meta.shortName()).isEqualTo("MS-Diffusion");
        assertThat(meta.sourceLevel()).isEqualTo("FULL_TEXT");
        assertThat(meta.summary()).isEqualTo("用 bbox mask 约束 attention 作用范围");
        assertThat(meta.chars()).isGreaterThan(0);
        assertThat(AnalysisStore.isAbstractOnly(meta.sourceLevel())).isFalse();
    }

    @Test
    void aFileWithoutTheHeaderReportsUnknownRatherThanGuessing() throws IOException {
        Path file = scratch.resolve("handwritten.md");
        Files.writeString(file, "# handwritten\n\nno header here\n", StandardCharsets.UTF_8);

        AnalysisStore.AnalysisMeta meta = AnalysisStore.readMeta(file);

        assertThat(meta.sourceLevel()).isEqualTo("UNKNOWN");
        assertThat(meta.summary()).isEmpty();
        // UNKNOWN must count as "we never read the body" — the conservative reading,
        // which is what keeps an unlabelled file out of the formula checks.
        assertThat(AnalysisStore.isAbstractOnly(meta.sourceLevel())).isTrue();
    }

    @Test
    void anUnreadableFileReportsUnknownInsteadOfThrowing() {
        AnalysisStore.AnalysisMeta meta = AnalysisStore.readMeta(scratch.resolve("missing.md"));

        assertThat(meta.sourceLevel()).isEqualTo("UNKNOWN");
        assertThat(meta.chars()).isZero();
    }

    @Test
    void onlyFullTextCountsAsReadableBody() {
        assertThat(AnalysisStore.isAbstractOnly("FULL_TEXT")).isFalse();
        assertThat(AnalysisStore.isAbstractOnly("full_text")).isFalse();
        assertThat(AnalysisStore.isAbstractOnly("ABSTRACT_ONLY")).isTrue();
        assertThat(AnalysisStore.isAbstractOnly("SNIPPET")).isTrue();
        assertThat(AnalysisStore.isAbstractOnly("UNKNOWN")).isTrue();
    }
}
