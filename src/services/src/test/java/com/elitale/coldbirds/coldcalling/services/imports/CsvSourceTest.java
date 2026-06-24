package com.elitale.coldbirds.coldcalling.services.imports;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

final class CsvSourceTest {

    @Test
    void parsesHeadersAndRows() throws IOException {
        CsvSource.Parsed parsed = CsvSource.parse("""
                First Name,Phone,Company
                Ann,+14155551234,Acme
                Bob,+14155559999,Globex
                """);

        assertThat(parsed.headers()).containsExactly("First Name", "Phone", "Company");
        assertThat(parsed.rows()).hasSize(2);
        assertThat(parsed.rows().get(0)).containsExactly("Ann", "+14155551234", "Acme");
    }

    @Test
    void handlesQuotedCommasAndEmbeddedNewlines() throws IOException {
        CsvSource.Parsed parsed = CsvSource.parse(
                "Name,Note\r\n\"Smith, Ann\",\"line1\nline2\"\r\n");

        assertThat(parsed.rows().get(0)).containsExactly("Smith, Ann", "line1\nline2");
    }

    @Test
    void emptyTextYieldsNoHeadersOrRows() throws IOException {
        CsvSource.Parsed parsed = CsvSource.parse("");
        assertThat(parsed.headers()).isEmpty();
        assertThat(parsed.rows()).isEmpty();
    }
}
