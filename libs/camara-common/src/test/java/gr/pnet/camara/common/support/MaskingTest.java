package gr.pnet.camara.common.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

    @Test
    void masksMiddleDigitsOfMsisdn() {
        assertThat(Masking.msisdn("+306912345678")).isEqualTo("+3069******78");
    }

    @Test
    void masksUrlEncodedMsisdnInsideText() {
        String message = "I/O error on GET request for \"http://tep/tep-api/v1/subscriber/%2B306912345678/status\"";

        assertThat(Masking.maskAll(message))
                .isEqualTo("I/O error on GET request for \"http://tep/tep-api/v1/subscriber/%2B3069******78/status\"");
    }

    @Test
    void leavesTextWithoutNumbersAndNullUntouched() {
        assertThat(Masking.maskAll("Read timed out")).isEqualTo("Read timed out");
        assertThat(Masking.maskAll(null)).isNull();
        assertThat(Masking.msisdn(null)).isNull();
    }
}
