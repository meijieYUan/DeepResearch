package com.itajay.superassistant.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Putting papers on a shelf: nothing that might be a paper is ever overwritten. */
class PaperShelfTest {

    @TempDir
    Path dir;

    @TempDir
    Path incoming;

    private PaperShelf shelf() {
        return new PaperShelf(dir, pdf -> PdfIdentityReader.identityOf(pdf)
                .map(printed -> new PaperShelf.Known(printed, PaperIdentity.NONE)));
    }

    private Path attention() throws IOException {
        return TestPdfs.paper(incoming.resolve("a.pdf"), "1706.03762", "Attention Is All You Need");
    }

    @Test
    void anErrorPageLeftUnderTheNameIsReplaced() throws IOException {
        Files.writeString(dir.resolve("Survey.pdf"), "<html>403 Forbidden</html>");

        assertThat(shelf().add(attention(), "Survey", PaperIdentity.NONE)).isEqualTo(dir.resolve("Survey.pdf"));
    }

    /** An encrypted PDF cannot be read either, and it may be one a human put there. */
    @Test
    void aPdfThatCannotBeReadIsKept() throws IOException {
        Files.writeString(dir.resolve("Survey.pdf"), "%PDF-1.7\nnot readable");

        assertThat(shelf().add(attention(), "Survey", PaperIdentity.NONE))
                .isEqualTo(dir.resolve("Survey-1706.03762.pdf"));
        assertThat(Files.readString(dir.resolve("Survey.pdf"))).contains("not readable");
    }

    @Test
    void papersWithoutAnyIdentifierStillDoNotCollide() throws IOException {
        Path first = shelf().add(TestPdfs.page(incoming.resolve("1.pdf"), "x"), "Scan", PaperIdentity.NONE);
        Path second = shelf().add(TestPdfs.page(incoming.resolve("2.pdf"), "y"), "Scan", PaperIdentity.NONE);

        assertThat(first).isEqualTo(dir.resolve("Scan.pdf"));
        assertThat(second).isNotEqualTo(first).exists();
    }

    @Test
    void theSameContentIsNotAddedTwice() throws IOException {
        Path scan = TestPdfs.page(incoming.resolve("1.pdf"), "x");

        assertThat(shelf().add(scan, "Other", PaperIdentity.NONE))
                .isEqualTo(shelf().add(scan, "Scan", PaperIdentity.NONE));
    }

    /** Several downloads run at once; choosing a name and taking it must not interleave. */
    @Test
    void papersAskingForOneNameAtTheSameTimeAllSurvive() throws Exception {
        int papers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(papers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Path>> added = new ArrayList<>();
            for (int i = 0; i < papers; i++) {
                String id = "2401.0000" + i;
                Path source = TestPdfs.paper(incoming.resolve(i + ".pdf"), id, "A Paper About Subject Number " + i);
                added.add(pool.submit((Callable<Path>) () -> {
                    start.await();
                    return shelf().add(source, "Survey", PaperIdentity.NONE);
                }));
            }
            start.countDown();
            List<String> ids = new ArrayList<>();
            for (Future<Path> future : added) {
                ids.add(PdfIdentityReader.identityOf(future.get()).orElseThrow().arxivId());
            }
            assertThat(ids).doesNotHaveDuplicates().hasSize(papers);
            try (var files = Files.list(dir)) {
                // No half-written temporary files either.
                assertThat(files.toList()).hasSize(papers);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aLongNameLeavesRoomForTheIdentifier() throws IOException {
        String name = "N".repeat(80);
        Files.writeString(dir.resolve(name + ".pdf"), "%PDF-1.7\nsomething else");

        assertThat(shelf().add(attention(), name, PaperIdentity.NONE).getFileName().toString())
                .endsWith("-1706.03762.pdf").hasSizeLessThanOrEqualTo(84);
    }

    @Test
    void whatIsNotAPdfIsRefused() throws IOException {
        Path fake = Files.writeString(incoming.resolve("fake.pdf"), "<html>404</html>");

        assertThatThrownBy(() -> shelf().add(fake, "Fake", PaperIdentity.NONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
