package gr.pnet.camara.roaming.delegate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * The countries a mobile country code (MCC) is assigned to, as ISO 3166-1 alpha-2 codes, from
 * {@code mcc-countries.properties}.
 */
final class MccCountries {

    private static final String RESOURCE = "/mcc-countries.properties";
    private static final Pattern MCC = Pattern.compile("\\d{3}");
    private static final Pattern ALPHA_2 = Pattern.compile("[A-Z]{2}");

    private static final Map<Integer, List<String>> COUNTRIES = load();

    private MccCountries() {
    }

    // Empty for an MCC with no country (e.g. 901, international networks) or not assigned at all
    static List<String> countriesFor(int mcc) {
        return COUNTRIES.getOrDefault(mcc, List.of());
    }

    private static Map<Integer, List<String>> load() {
        Properties properties = new Properties();
        try (InputStream in = MccCountries.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Map<Integer, List<String>> countries = new HashMap<>();
        for (String mcc : properties.stringPropertyNames()) {
            String value = properties.getProperty(mcc).trim();
            List<String> codes = value.isEmpty() ? List.of() : List.of(value.split("\\s*,\\s*"));
            if (!MCC.matcher(mcc).matches() || !codes.stream().allMatch(c -> ALPHA_2.matcher(c).matches())) {
                throw new IllegalStateException("Bad entry in " + RESOURCE + ": " + mcc + "=" + value);
            }
            countries.put(Integer.valueOf(mcc), codes);
        }
        return Map.copyOf(countries);
    }
}
