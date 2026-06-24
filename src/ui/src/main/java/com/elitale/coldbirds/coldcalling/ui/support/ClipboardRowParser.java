package com.elitale.coldbirds.coldcalling.ui.support;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses clipboard text pasted into the leads grid into rows of cells.
 *
 * <p>Lines split on {@code \r\n} / {@code \n}; cells split on TAB (the format produced
 * by spreadsheets and most lead exports). Blank lines are skipped, cells are trimmed.
 * One pasted line becomes one row; the controller maps cells left-to-right onto fields.
 */
public final class ClipboardRowParser {

    private ClipboardRowParser() {
    }

    public static List<List<String>> parse(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : text.split("\\r?\\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            List<String> cells = new ArrayList<>();
            for (String cell : line.split("\t", -1)) {
                cells.add(cell.strip());
            }
            rows.add(List.copyOf(cells));
        }
        return List.copyOf(rows);
    }
}
