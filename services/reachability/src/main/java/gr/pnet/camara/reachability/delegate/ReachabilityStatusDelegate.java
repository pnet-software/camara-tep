package gr.pnet.camara.reachability.delegate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.AccessTokenSubject;
import gr.pnet.camara.reachability.generated.status.DeviceReachabilityStatusApiDelegate;
import gr.pnet.camara.reachability.generated.status.model.ConnectivityType;
import gr.pnet.camara.reachability.generated.status.model.Device;
import gr.pnet.camara.reachability.generated.status.model.DeviceResponse;
import gr.pnet.camara.reachability.generated.status.model.ReachabilityStatusResponse;
import gr.pnet.camara.reachability.generated.status.model.RequestReachabilityStatus;
import gr.pnet.camara.tep.TepCorrelator;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.model.TepDevice;
import gr.pnet.tepclient.model.TepSubscriberStatus;

@Service
public class ReachabilityStatusDelegate implements DeviceReachabilityStatusApiDelegate {

    private final SubscribersApi subscriberApi;
    private final AccessTokenSubject tokenSubject;

    public ReachabilityStatusDelegate(SubscribersApi subscriberApi, AccessTokenSubject tokenSubject) {
        this.subscriberApi = subscriberApi;
        this.tokenSubject = tokenSubject;
    }

    @Override
    public ResponseEntity<ReachabilityStatusResponse> getReachabilityStatus(
            RequestReachabilityStatus requestReachabilityStatus, String xCorrelator) {
        ResolvedDevice device = resolveDevice(requestReachabilityStatus);

        TepSubscriberStatus status;
        try {
            status = subscriberApi.getSubscriberStatus(new TepDevice().phoneNumber(device.phoneNumber()), TepCorrelator.current());
        } catch (HttpClientErrorException.NotFound e) {
            throw CamaraApiException.identifierNotFound();
        } catch (RestClientException e) {
            throw CamaraApiException.unavailable(e);
        }

        List<ConnectivityType> connectivity = ReachabilityMapper.toConnectivity(status);
        // TEP exposes no status timestamp, so the status is as of this call
        ReachabilityStatusResponse response = new ReachabilityStatusResponse(
                OffsetDateTime.now(), !connectivity.isEmpty())
                .connectivity(connectivity);
        if (device.echoInResponse()) {
            response.device(new DeviceResponse().phoneNumber(device.phoneNumber()));
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Single place that decides which subscriber the request is about: the three-legged token's subscriber,
     * or, with a two-legged token, the device in the body.
     */
    private ResolvedDevice resolveDevice(RequestReachabilityStatus request) {
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
