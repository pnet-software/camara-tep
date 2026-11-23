package gr.pnet.camara.roaming.delegate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

// Loading the table also checks every entry: a malformed one fails these tests
class MccCountriesTest {

    @Test
    void singleCountryMcc() {
        assertThat(MccCountries.countriesFor(202)).containsExactly("GR");
        assertThat(MccCountries.countriesFor(262)).containsExactly("DE");
    }

    // The examples in the CAMARA spec
    @Test
    void sharedMccListsEveryCountry() {
        assertThat(MccCountries.countriesFor(340)).containsExactlyInAnyOrder("BL", "GF", "GP", "MF", "MQ");
    }

    @Test
    void internationalMccHasNoCountry() {
        assertThat(MccCountries.countriesFor(901)).isEmpty();
    }

    @Test
    void testAndUnassignedMccsHaveNoCountry() {
        assertThat(MccCountries.countriesFor(1)).isEmpty();
        assertThat(MccCountries.countriesFor(999)).isEmpty();
        assertThat(MccCountries.countriesFor(100)).isEmpty();
    }
}
