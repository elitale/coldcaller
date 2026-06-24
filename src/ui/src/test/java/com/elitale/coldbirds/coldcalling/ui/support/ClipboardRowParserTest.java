package com.elitale.coldbirds.coldcalling.ui.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClipboardRowParserTest {

    @Test
    void blankOrNullYieldsNoRows() {
        assertThat(ClipboardRowParser.parse(null)).isEmpty();
        assertThat(ClipboardRowParser.parse("   ")).isEmpty();
    }

    @Test
    void singleLineSplitsCellsOnTab() {
        assertThat(ClipboardRowParser.parse("+14155550100\tAda\tLovelace\tAcme"))
                .containsExactly(List_of("+14155550100", "Ada", "Lovelace", "Acme"));
    }

    @Test
    void multipleLinesBecomeMultipleRowsAndSkipBlankLines() {
        String pasted = "+14155550100\tAda\n\n+14155550101\tGrace\r\n";
        assertThat(ClipboardRowParser.parse(pasted))
                .containsExactly(
                        List_of("+14155550100", "Ada"),
                        List_of("+14155550101", "Grace"));
    }

    @Test
    void cellsAreTrimmed() {
        assertThat(ClipboardRowParser.parse("  +14155550100  \t  Ada  "))
                .containsExactly(List_of("+14155550100", "Ada"));
    }

    private static java.util.List<String> List_of(String... s) {
        return java.util.List.of(s);
    }
}
