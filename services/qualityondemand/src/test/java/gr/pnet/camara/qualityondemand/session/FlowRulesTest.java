package gr.pnet.camara.qualityondemand.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import gr.pnet.camara.qualityondemand.session.Ports.Range;

class FlowRulesTest {

    @Test
    void networksOverlapWhenOneContainsTheOther() {
        assertThat(FlowRules.networksOverlap("200.24.24.0/24", "200.24.24.64/26")).isTrue();
        assertThat(FlowRules.networksOverlap("200.24.24.7", "200.24.24.0/24")).isTrue();
        assertThat(FlowRules.networksOverlap("200.24.24.7", "200.24.24.7")).isTrue();
        assertThat(FlowRules.networksOverlap("0.0.0.0/0", "10.0.0.1")).isTrue();
    }

    @Test
    void separateNetworksDontOverlap() {
        assertThat(FlowRules.networksOverlap("200.24.24.0/24", "200.24.25.0/24")).isFalse();
        assertThat(FlowRules.networksOverlap("200.24.24.7", "200.24.24.8")).isFalse();
    }

    @Test
    void portsOverlapBetweenListsAndRanges() {
        Ports sip = new Ports(List.of(5060), List.of());
        Ports range = new Ports(List.of(), List.of(new Range(5000, 5100)));
        Ports other = new Ports(List.of(8080), List.of(new Range(9000, 9100)));

        assertThat(FlowRules.portsOverlap(sip, range)).isTrue();
        assertThat(FlowRules.portsOverlap(range, sip)).isTrue();
        assertThat(FlowRules.portsOverlap(sip, other)).isFalse();
        assertThat(FlowRules.portsOverlap(range, other)).isFalse();
    }

    // No ports at all means every port
    @Test
    void anyPortsOverlapEverything() {
        assertThat(FlowRules.portsOverlap(Ports.ANY, new Ports(List.of(1), List.of()))).isTrue();
        assertThat(FlowRules.portsOverlap(null, Ports.ANY)).isTrue();
    }

    @Test
    void flowsConflictOnlyWhenServersAndBothPortsOverlap() {
        QodSession existing = session("200.24.24.0/24", Ports.ANY, new Ports(List.of(443), List.of()));

        assertThat(FlowRules.overlap("200.24.24.10", Ports.ANY, new Ports(List.of(443), List.of()), existing))
                .isTrue();
        assertThat(FlowRules.overlap("200.24.24.10", Ports.ANY, new Ports(List.of(80), List.of()), existing))
                .isFalse();
        assertThat(FlowRules.overlap("10.0.0.1", Ports.ANY, Ports.ANY, existing)).isFalse();
    }

    // TS 29.214: "permit out" both ways, the direction given by which end is the source
    @Test
    void flowDescriptionsAreDownlinkThenUplink() {
        assertThat(FlowRules.flowDescriptions("10.45.0.9", Ports.ANY, "200.24.24.0/24", Ports.ANY))
                .containsExactly("permit out ip from 200.24.24.0/24 to 10.45.0.9/32",
                        "permit out ip from 10.45.0.9/32 to 200.24.24.0/24");
    }

    @Test
    void flowDescriptionsCarryPortsAndANetworkAddress() {
        Ports devicePorts = new Ports(List.of(5060, 5070), List.of(new Range(5010, 5020), new Range(7000, 7000)));
        Ports serverPorts = new Ports(List.of(443), List.of());

        assertThat(FlowRules.flowDescriptions("10.45.0.9", devicePorts, "200.24.24.7/24", serverPorts))
                .containsExactly("permit out ip from 200.24.24.0/24 443 to 10.45.0.9/32 5060,5070,5010-5020,7000",
                        "permit out ip from 10.45.0.9/32 5060,5070,5010-5020,7000 to 200.24.24.0/24 443");
    }

    @Test
    void networkOfASingleAddressIsSlash32() {
        assertThat(FlowRules.network("200.24.24.7")).isEqualTo("200.24.24.7/32");
        assertThat(FlowRules.network("200.24.24.7/0")).isEqualTo("0.0.0.0/0");
    }

    @Test
    void malformedAddressesAreRejected() {
        for (String bad : new String[] { "200.24.24", "200.24.24.256", "200.24.24.0/33", "a.b.c.d", "2001:db8::1" }) {
            assertThatThrownBy(() -> FlowRules.network(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static QodSession session(String server, Ports devicePorts, Ports serverPorts) {
        return SessionFixtures.available("+306912345678", server, devicePorts, serverPorts);
    }
}
