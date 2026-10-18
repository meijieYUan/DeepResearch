package com.itajay.superassistant.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SSRF guard is shared by paper downloads and web crawling. These cases use
 * literal addresses so the test never performs a DNS lookup.
 */
class UrlSafetyTest {

    @Test
    void allowsAPublicAddress() {
        assertThat(UrlSafety.rejectionReason("https://93.184.216.34/paper.pdf")).isNull();
    }

    @Test
    void blocksLoopbackPrivateAndMetadataAddresses() {
        assertThat(UrlSafety.rejectionReason("http://127.0.0.1:8080/api"))
                .contains("non-public");
        assertThat(UrlSafety.rejectionReason("http://10.1.2.3/"))
                .contains("non-public");
        assertThat(UrlSafety.rejectionReason("http://192.168.1.1/"))
                .contains("non-public");
        assertThat(UrlSafety.rejectionReason("http://169.254.169.254/latest/meta-data/"))
                .contains("non-public");
        assertThat(UrlSafety.rejectionReason("http://0.0.0.1/"))
                .contains("non-public");
        assertThat(UrlSafety.rejectionReason("http://100.64.0.1/"))
                .contains("non-public");
    }

    @Test
    void blocksIpv6LoopbackAndUniqueLocalAddresses() {
        assertThat(UrlSafety.rejectionReason("http://[::1]/"))
                .contains("non-public");
        assertThat(UrlSafety.rejectionReason("http://[fc00::1]/"))
                .contains("non-public");
        assertThat(UrlSafety.rejectionReason("http://[fd12:3456::1]/"))
                .contains("non-public");
    }

    @Test
    void rejectsNonHttpSchemesAndMalformedUrls() {
        assertThat(UrlSafety.rejectionReason("file:///etc/passwd"))
                .contains("unsupported scheme");
        assertThat(UrlSafety.rejectionReason("gopher://example.com/"))
                .contains("unsupported scheme");
        assertThat(UrlSafety.rejectionReason("not a url")).isEqualTo("invalid URL");
        assertThat(UrlSafety.rejectionReason("")).isEqualTo("missing URL");
    }
}
