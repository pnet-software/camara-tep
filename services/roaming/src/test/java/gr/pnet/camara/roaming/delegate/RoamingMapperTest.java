package gr.pnet.camara.roaming.delegate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import gr.pnet.camara.roaming.delegate.RoamingMapper.ServingNetwork;
import gr.pnet.tepclient.model.TepSubscriberStatus;

class RoamingMapperTest {

    @Test
    void notRoamingWhereTepSaysSo() {
        assertThat(RoamingMapper.servingNetwork(status("202", false))).contains(new ServingNetwork(202, false));
    }

    @Test
    void roamingWithTheServingCountryCode() {
        assertThat(RoamingMapper.servingNetwork(status("262", true))).contains(new ServingNetwork(262, true));
    }

    @Test
    void emptyWhenTepCanNotTellWhetherTheUeIsRoaming() {
        // Not registered, or TEP has no home PLMNs
        assertThat(RoamingMapper.servingNetwork(status("202", null))).isEmpty();
        assertThat(RoamingMapper.servingNetwork(new TepSubscriberStatus())).isEmpty();
    }

    @Test
    void emptyWithoutAValidServingMcc() {
        assertThat(RoamingMapper.servingNetwork(status(null, true))).isEmpty();
        assertThat(RoamingMapper.servingNetwork(status("", true))).isEmpty();
        assertThat(RoamingMapper.servingNetwork(status("20", true))).isEmpty();
        assertThat(RoamingMapper.servingNetwork(status("2O2", true))).isEmpty();
    }

    private static TepSubscriberStatus status(String mcc, Boolean roaming) {
        return new TepSubscriberStatus().mcc(mcc).mnc("01").roaming(roaming);
    }
}
