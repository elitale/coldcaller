package com.elitale.coldbirds.coldcalling.ui.support;

import com.elitale.coldbirds.coldcalling.domain.value.Country;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reference catalog of dialable countries for the dialer's country-code picker.
 * <p>
 * Data is loaded once from {@code /data/countries.tsv} (ISO alpha-2, display
 * name, E.164 dial code, and a representative timezone offset). Flags are not
 * stored — each {@link Country} derives its emoji from its ISO code. The list
 * is sorted alphabetically by display name.
 */
public final class CountryCatalog {

    private static final String RESOURCE = "/data/countries.tsv";

    /** All catalog countries, sorted by display name. Unmodifiable. */
    public static final List<Country> ALL = load();

    private CountryCatalog() {}

    private static List<Country> load() {
        List<Country> countries = new ArrayList<>();
        InputStream in = CountryCatalog.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Missing country data resource: " + RESOURCE);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split("\t");
                if (parts.length != 4) {
                    continue;
                }
                countries.add(new Country(
                        parts[0].strip(),
                        parts[1].strip(),
                        parts[2].strip(),
                        parts[3].strip()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read country data resource", e);
        }
        countries.sort(Comparator.comparing(Country::displayName));
        return List.copyOf(countries);
    }

    /** Look up a country by ISO alpha-2 code (case-insensitive). */
    public static Optional<Country> byIso(String isoCode) {
        if (isoCode == null) {
            return Optional.empty();
        }
        return ALL.stream()
                .filter(c -> c.isoCode().equalsIgnoreCase(isoCode))
                .findFirst();
    }
}
