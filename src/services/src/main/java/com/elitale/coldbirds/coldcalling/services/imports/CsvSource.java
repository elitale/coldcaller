package com.elitale.coldbirds.coldcalling.services.imports;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Reads a delimited file into headers + raw string rows using commons-csv
 * (handles quoting, embedded newlines, escaping). The first record is the header.
 */
public final class CsvSource {

    /** Parsed file: header names and the data rows beneath them. */
    public record Parsed(List<String> headers, List<List<String>> rows) {
        public Parsed {
            headers = List.copyOf(headers);
            rows = rows.stream().map(List::copyOf).toList();
        }
    }

    private CsvSource() {}

    public static Parsed parse(String text) throws IOException {
        return parse(new StringReader(Objects.requireNonNull(text, "text must not be null")));
    }

    public static Parsed parse(Reader reader) throws IOException {
        Objects.requireNonNull(reader, "reader must not be null");
        final CSVFormat format = CSVFormat.DEFAULT.builder()
                .setIgnoreSurroundingSpaces(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .build();
        try (CSVParser parser = format.parse(reader)) {
            final List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) {
                return new Parsed(List.of(), List.of());
            }
            final List<String> headers = toList(records.get(0));
            final List<List<String>> rows = new ArrayList<>();
            for (int i = 1; i < records.size(); i++) {
                rows.add(toList(records.get(i)));
            }
            return new Parsed(headers, rows);
        }
    }

    private static List<String> toList(CSVRecord record) {
        final List<String> out = new ArrayList<>(record.size());
        for (String value : record) {
            out.add(value == null ? "" : value);
        }
        return out;
    }
}
