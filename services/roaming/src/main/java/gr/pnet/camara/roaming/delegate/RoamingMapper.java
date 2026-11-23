package gr.pnet.camara.roaming.delegate;

import java.util.Optional;
import java.util.regex.Pattern;

import gr.pnet.tepclient.model.TepSubscriberStatus;

/**
 * Maps the TEP core-network view of a subscriber onto the CAMARA roaming status.
 */
final class RoamingMapper {

    private static final Pattern MCC = Pattern.compile("\\d{3}");

    private RoamingMapper() {
    }

    record ServingNetwork(int countryCode, boolean roaming) {
    }

    // Whether the UE is roaming is TEP's: it knows the home PLMNs. The country is that of the serving MCC.
    // Empty when TEP can't tell (the UE isn't registered, or TEP has no home PLMNs) or gives no valid MCC.
    static Optional<ServingNetwork> servingNetwork(TepSubscriberStatus status) {
        String mcc = status.getMcc();
        Boolean roaming = status.getRoaming();
        if (roaming == null || mcc == null || !MCC.matcher(mcc).matches()) {
            return Optional.empty();
        }
        return Optional.of(new ServingNetwork(Integer.parseInt(mcc), roaming));
    }
}
