package com.itajay.superassistant.tool;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.util.Matrix;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One-page PDFs laid out like papers: lines above an abstract, and arXiv's vertical margin stamp. */
public final class TestPdfs {

    private TestPdfs() {
    }

    /**
     * @param stampId   arXiv id to stamp up the left margin, or null for none
     * @param titleArea what is printed above the abstract, one entry per line
     */
    public static Path paper(Path file, String stampId, String... titleArea) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                float y = 720;
                for (String line : titleArea) {
                    write(content, font, 16, Matrix.getTranslateInstance(90, y), line);
                    y -= 22;
                }
                write(content, font, 12, Matrix.getTranslateInstance(90, y - 20), "Abstract");
                for (int i = 0; i < 14; i++) {
                    write(content, font, 10, Matrix.getTranslateInstance(90, y - 44 - i * 14),
                            "We study the problem in detail and report results on several public benchmarks.");
                }
                if (stampId != null) {
                    write(content, font, 18, Matrix.getRotateInstance(Math.toRadians(90), 34, 200),
                            "arXiv:" + stampId + "v2  [cs.CV]  2 Aug 2023");
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** A page with exactly these lines and nothing else. */
    public static Path page(Path file, String... lines) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                float y = 720;
                for (String line : lines) {
                    write(content, font, 12, Matrix.getTranslateInstance(90, y), line);
                    y -= 20;
                }
            }
            doc.save(file.toFile());
        }
        return file;
    }

    /** A page that says nothing about itself, like a scan. */
    public static Path blank(Path file) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(file.toFile());
        }
        return file;
    }

    private static void write(PDPageContentStream content, PDType1Font font, float size, Matrix at, String text)
            throws IOException {
        content.beginText();
        content.setFont(font, size);
        content.setTextMatrix(at);
        content.showText(text);
        content.endText();
    }
}
