package gr.pnet.camara.qosprofiles.delegate;

import gr.pnet.camara.qosprofiles.generated.status.model.Duration;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfile;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfileStatusEnum;
import gr.pnet.camara.qosprofiles.generated.status.model.Rate;
import gr.pnet.camara.qosprofiles.generated.status.model.RateUnitEnum;
import gr.pnet.camara.qosprofiles.generated.status.model.TimeUnitEnum;
import gr.pnet.tepclient.model.TepQualityProfile;
import gr.pnet.tepclient.model.TepQualityProfileDuration;
import gr.pnet.tepclient.model.TepQualityProfileRate;

/**
 * Maps a TEP quality profile onto a CAMARA QoS profile. The fields TEP has match CAMARA's one to one, and the
 * units' enum constants have the same names (MBPS, MILLISECONDS), so enums go by constant name.
 */
final class QosProfileMapper {

    private QosProfileMapper() {
    }

    // TEP's fiveQi and pre-emption settings have no CAMARA field; CAMARA's burst rates, countryAvailability,
    // l4sQueueType and serviceClass no TEP one
    static QosProfile toQosProfile(TepQualityProfile tep) {
        QosProfileStatusEnum status = tep.getStatus() == null ? null
                : QosProfileStatusEnum.valueOf(tep.getStatus().name());
        return new QosProfile(tep.getName(), status)
                .description(tep.getDescription())
                .targetMinUpstreamRate(rate(tep.getTargetMinUpstreamRate()))
                .maxUpstreamRate(rate(tep.getMaxUpstreamRate()))
                .targetMinDownstreamRate(rate(tep.getTargetMinDownstreamRate()))
                .maxDownstreamRate(rate(tep.getMaxDownstreamRate()))
                .minDuration(duration(tep.getMinDuration()))
                .maxDuration(duration(tep.getMaxDuration()))
                .priority(tep.getPriority())
                .packetDelayBudget(duration(tep.getPacketDelayBudget()))
                .jitter(duration(tep.getJitter()))
                .packetErrorLossRate(tep.getPacketErrorLossRate());
    }

    private static Rate rate(TepQualityProfileRate tep) {
        if (tep == null) {
            return null;
        }
        return new Rate().value(tep.getValue())
                .unit(tep.getUnit() == null ? null : RateUnitEnum.valueOf(tep.getUnit().name()));
    }

    private static Duration duration(TepQualityProfileDuration tep) {
        if (tep == null) {
            return null;
        }
        return new Duration().value(tep.getValue())
                .unit(tep.getUnit() == null ? null : TimeUnitEnum.valueOf(tep.getUnit().name()));
    }
}
