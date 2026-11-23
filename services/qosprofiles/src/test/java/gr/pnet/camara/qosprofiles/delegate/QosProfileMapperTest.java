package gr.pnet.camara.qosprofiles.delegate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import gr.pnet.camara.qosprofiles.generated.status.model.Duration;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfile;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfileStatusEnum;
import gr.pnet.camara.qosprofiles.generated.status.model.Rate;
import gr.pnet.camara.qosprofiles.generated.status.model.RateUnitEnum;
import gr.pnet.camara.qosprofiles.generated.status.model.TimeUnitEnum;
import gr.pnet.tepclient.model.TepQualityProfile;
import gr.pnet.tepclient.model.TepQualityProfileDuration;
import gr.pnet.tepclient.model.TepQualityProfileRate;

class QosProfileMapperTest {

    @Test
    void mapsEveryField() {
        TepQualityProfile tep = new TepQualityProfile()
                .name("voice")
                .description("QoS profile for high-quality interactive voice")
                .status(TepQualityProfile.StatusEnum.ACTIVE)
                .targetMinUpstreamRate(rate(100, TepQualityProfileRate.UnitEnum.KBPS))
                .maxUpstreamRate(rate(1, TepQualityProfileRate.UnitEnum.MBPS))
                .targetMinDownstreamRate(rate(200, TepQualityProfileRate.UnitEnum.KBPS))
                .maxDownstreamRate(rate(10, TepQualityProfileRate.UnitEnum.MBPS))
                .minDuration(duration(1, TepQualityProfileDuration.UnitEnum.DAYS))
                .maxDuration(duration(10, TepQualityProfileDuration.UnitEnum.DAYS))
                .priority(10)
                .packetDelayBudget(duration(50, TepQualityProfileDuration.UnitEnum.MILLISECONDS))
                .jitter(duration(5, TepQualityProfileDuration.UnitEnum.MILLISECONDS))
                .packetErrorLossRate(3)
                .fiveQi(1)
                .preemptionCapability(TepQualityProfile.PreemptionCapabilityEnum.NOT_PREEMPT)
                .preemptionVulnerability(TepQualityProfile.PreemptionVulnerabilityEnum.PREEMPTABLE);

        QosProfile profile = QosProfileMapper.toQosProfile(tep);

        assertThat(profile.getName()).isEqualTo("voice");
        assertThat(profile.getDescription()).isEqualTo("QoS profile for high-quality interactive voice");
        assertThat(profile.getStatus()).isEqualTo(QosProfileStatusEnum.ACTIVE);
        assertThat(profile.getTargetMinUpstreamRate()).isEqualTo(new Rate().value(100).unit(RateUnitEnum.KBPS));
        assertThat(profile.getMaxUpstreamRate()).isEqualTo(new Rate().value(1).unit(RateUnitEnum.MBPS));
        assertThat(profile.getTargetMinDownstreamRate()).isEqualTo(new Rate().value(200).unit(RateUnitEnum.KBPS));
        assertThat(profile.getMaxDownstreamRate()).isEqualTo(new Rate().value(10).unit(RateUnitEnum.MBPS));
        assertThat(profile.getMinDuration()).isEqualTo(new Duration().value(1).unit(TimeUnitEnum.DAYS));
        assertThat(profile.getMaxDuration()).isEqualTo(new Duration().value(10).unit(TimeUnitEnum.DAYS));
        assertThat(profile.getPriority()).isEqualTo(10);
        assertThat(profile.getPacketDelayBudget())
                .isEqualTo(new Duration().value(50).unit(TimeUnitEnum.MILLISECONDS));
        assertThat(profile.getJitter()).isEqualTo(new Duration().value(5).unit(TimeUnitEnum.MILLISECONDS));
        assertThat(profile.getPacketErrorLossRate()).isEqualTo(3);
        // No TEP source for these
        assertThat(profile.getMaxUpstreamBurstRate()).isNull();
        assertThat(profile.getMaxDownstreamBurstRate()).isNull();
        assertThat(profile.getCountryAvailability()).isNull();
        assertThat(profile.getL4sQueueType()).isNull();
        assertThat(profile.getServiceClass()).isNull();
    }

    @Test
    void optionalFieldsTepLeavesOutStayUnset() {
        QosProfile profile = QosProfileMapper.toQosProfile(new TepQualityProfile()
                .name("voice")
                .status(TepQualityProfile.StatusEnum.INACTIVE));

        assertThat(profile).isEqualTo(new QosProfile("voice", QosProfileStatusEnum.INACTIVE));
    }

    // Every TEP constant must have a CAMARA one of the same name; these fail if either spec adds one

    @ParameterizedTest
    @EnumSource(TepQualityProfile.StatusEnum.class)
    void everyStatusMaps(TepQualityProfile.StatusEnum status) {
        assertThat(QosProfileMapper.toQosProfile(new TepQualityProfile().name("voice").status(status))
                .getStatus().name()).isEqualTo(status.name());
    }

    @ParameterizedTest
    @EnumSource(TepQualityProfileRate.UnitEnum.class)
    void everyRateUnitMaps(TepQualityProfileRate.UnitEnum unit) {
        assertThat(QosProfileMapper.toQosProfile(new TepQualityProfile().name("voice")
                .status(TepQualityProfile.StatusEnum.ACTIVE)
                .maxDownstreamRate(rate(1, unit))).getMaxDownstreamRate().getUnit().name()).isEqualTo(unit.name());
    }

    @ParameterizedTest
    @EnumSource(TepQualityProfileDuration.UnitEnum.class)
    void everyTimeUnitMaps(TepQualityProfileDuration.UnitEnum unit) {
        assertThat(QosProfileMapper.toQosProfile(new TepQualityProfile().name("voice")
                .status(TepQualityProfile.StatusEnum.ACTIVE)
                .maxDuration(duration(1, unit))).getMaxDuration().getUnit().name()).isEqualTo(unit.name());
    }

    private static TepQualityProfileRate rate(int value, TepQualityProfileRate.UnitEnum unit) {
        return new TepQualityProfileRate().value(value).unit(unit);
    }

    private static TepQualityProfileDuration duration(int value, TepQualityProfileDuration.UnitEnum unit) {
        return new TepQualityProfileDuration().value(value).unit(unit);
    }
}
