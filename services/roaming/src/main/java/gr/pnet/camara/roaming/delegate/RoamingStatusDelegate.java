package gr.pnet.camara.roaming.delegate;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.AccessTokenSubject;
import gr.pnet.camara.roaming.delegate.RoamingMapper.ServingNetwork;
import gr.pnet.camara.roaming.generated.status.RoamingStatusRetrievalApiDelegate;
import gr.pnet.camara.roaming.generated.status.model.Device;
import gr.pnet.camara.roaming.generated.status.model.DeviceResponse;
import gr.pnet.camara.roaming.generated.status.model.RoamingStatusRequest;
import gr.pnet.camara.roaming.generated.status.model.RoamingStatusResponse;
import gr.pnet.camara.tep.TepCorrelator;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.model.TepDevice;
import gr.pnet.tepclient.model.TepSubscriberStatus;

@Service
public class RoamingStatusDelegate implements RoamingStatusRetrievalApiDelegate {

    private static final Logger log = LoggerFactory.getLogger(RoamingStatusDelegate.class);

    private final SubscribersApi subscriberApi;
    private final AccessTokenSubject tokenSubject;

    public RoamingStatusDelegate(SubscribersApi subscriberApi, AccessTokenSubject tokenSubject) {
        this.subscriberApi = subscriberApi;
        this.tokenSubject = tokenSubject;
    }

    @Override
    public ResponseEntity<RoamingStatusResponse> getRoamingStatus(RoamingStatusRequest request, String correlator) {
        ResolvedDevice device = resolveDevice(request);

        TepSubscriberStatus status;
        try {
            status = subscriberApi.getSubscriberStatus(new TepDevice().phoneNumber(device.phoneNumber()), TepCorrelator.current());
        } catch (HttpClientErrorException.NotFound e) {
            throw CamaraApiException.identifierNotFound();
        } catch (RestClientException e) {
            throw CamaraApiException.unavailable(e);
        }

        ServingNetwork network = RoamingMapper.servingNetwork(status).orElseThrow(() -> {
            log.info("SERVICE_NOT_APPLICABLE: TEP can't tell whether the device is roaming (mcc {}, roaming {})",
                    status.getMcc(), status.getRoaming());
            return CamaraApiException.serviceNotApplicable();
        });
        // TEP exposes no status timestamp, so the status is as of this call
        RoamingStatusResponse response = new RoamingStatusResponse(OffsetDateTime.now(), network.roaming());
        // The visited country only when roaming; the generated model would otherwise send "countryName": []
        if (network.roaming()) {
            response.countryCode(network.countryCode())
                    .countryName(MccCountries.countriesFor(network.countryCode()));
        } else {
            response.countryName(null);
        }
        if (device.echoInResponse()) {
            response.device(new DeviceResponse().phoneNumber(device.phoneNumber()));
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Single place that decides which subscriber the request is about: the three-legged token's subscriber,
     * or, with a two-legged token, the device in the body.
     */
    private ResolvedDevice resolveDevice(RoamingStatusRequest request) {
        Device device = request == null ? null : request.getDevice();
        Optional<String> tokenPhoneNumber = tokenSubject.phoneNumber();
        if (tokenPhoneNumber.isPresent()) {
            // CAMARA: the body must not name a device the token already identifies, even the same one
            if (device != null) {
                throw CamaraApiException.unnecessaryIdentifier();
            }
            return new ResolvedDevice(tokenPhoneNumber.get(), false);
        }
        if (device == null) {
            throw CamaraApiException.missingIdentifier();
        }
        long identifiers = Stream.of(device.getPhoneNumber(), device.getNetworkAccessIdentifier(),
                device.getIpv4Address(), device.getIpv6Address())
                .filter(id -> id != null)
                .count();
        if (identifiers == 0) {
            throw CamaraApiException.missingIdentifier();
        }
        if (device.getPhoneNumber() == null) {
            throw CamaraApiException.unsupportedIdentifier();
        }
        // CAMARA returns the identifier used only when the consumer sent several
        return new ResolvedDevice(device.getPhoneNumber(), identifiers > 1);
    }

    private record ResolvedDevice(String phoneNumber, boolean echoInResponse) {
    }
}
