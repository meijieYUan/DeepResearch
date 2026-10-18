package com.itajay.superassistant.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AcademicSearchToolTest {

    @Test
    void parsesArxivAtomEntries() {
        String xml = """
                <feed xmlns="http://www.w3.org/2005/Atom">
                  <entry>
                    <id>http://arxiv.org/abs/2401.00001v1</id>
                    <published>2024-01-01T00:00:00Z</published>
                    <title>Context Management for Agents</title>
                    <summary>An abstract about context management.</summary>
                    <author><name>Alice</name></author>
                    <author><name>Bob</name></author>
                    <link href="http://arxiv.org/pdf/2401.00001v1" title="pdf"/>
                  </entry>
                </feed>
                """;

        String parsed = AcademicSearchTool.parseArxiv(xml, 10);

        assertThat(parsed).contains("Context Management for Agents", "Alice, Bob",
                "2024-01-01", "http://arxiv.org/abs/2401.00001v1",
                "http://arxiv.org/pdf/2401.00001v1", "An abstract about context management.");
    }

    @Test
    void parsesSemanticScholarGraphJson() throws Exception {
        String json = """
                {"data":[{
                  "title":"Agent Memory",
                  "authors":[{"name":"Carol"}],
                  "year":2025,
                  "venue":"ICML",
                  "citationCount":42,
                  "abstract":"Memory for agents.",
                  "url":"https://example.org/paper",
                  "openAccessPdf":{"url":"https://example.org/paper.pdf"}
                }]}
                """;

        String parsed = AcademicSearchTool.parseSemanticScholar(json, 10);

        assertThat(parsed).contains("Agent Memory", "Carol", "2025", "ICML", "42",
                "https://example.org/paper", "https://example.org/paper.pdf", "Memory for agents.");
    }

    @Test
    void emptyResponsesAreReportedAsEmpty() throws Exception {
        assertThat(AcademicSearchTool.parseArxiv("<feed/>", 5)).isEqualTo("No arXiv results.");
        assertThat(AcademicSearchTool.parseSemanticScholar("{\"data\":[]}", 5))
                .isEqualTo("No Semantic Scholar results.");
    }
}
