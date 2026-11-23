package gr.pnet.camara.locationverification.delegate;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.AccessTokenSubject;
import gr.pnet.camara.locationverification.delegate.LocationMapper.GeoCircle;
import gr.pnet.camara.locationverification.delegate.LocationMapper.Verification;
import gr.pnet.camara.locationverification.generated.status.LocationVerificationApiDelegate;
import gr.pnet.camara.locationverification.generated.status.model.Circle;
import gr.pnet.camara.locationverification.generated.status.model.Device;
import gr.pnet.camara.locationverification.generated.status.model.DeviceResponse;
import gr.pnet.camara.locationverification.generated.status.model.VerifyLocationRequest;
import gr.pnet.camara.locationverification.generated.status.model.VerifyLocationResponse;
import gr.pnet.camara.tep.TepCorrelator;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.model.TepDevice;
import gr.pnet.tepclient.model.TepSubscriberLocation;

@Service
public class LocationVerificationDelegate implements LocationVerificationApiDelegate {

    private static final Logger log = LoggerFactory.getLogger(LocationVerificationDelegate.class);

    private final SubscribersApi subscriberApi;
    private final AccessTokenSubject tokenSubject;

    public LocationVerificationDelegate(SubscribersApi subscriberApi, AccessTokenSubject tokenSubject) {
        this.subscriberApi = subscriberApi;
        this.tokenSubject = tokenSubject;
    }

    @Override
    public ResponseEntity<VerifyLocationResponse> verifyLocation(VerifyLocationRequest request, String correlator) {
        ResolvedDevice device = resolveDevice(request);

        // No location can be fresher than now
        Integer maxAge = request.getMaxAge();
        if (maxAge != null && maxAge < 0) {
            throw unableToFulfillMaxAge();
        }
        // CIRCLE is the only area type, and the controller has already validated it
        Circle circle = (Circle) request.getArea();
        GeoCircle area = new GeoCircle(circle.getCenter().getLatitude(), circle.getCenter().getLongitude(),
                circle.getRadius().doubleValue());

        TepSubscriberLocation location = locate(device.phoneNumber());
        OffsetDateTime lastLocationTime = lastLocationTime(location.getLastUpdated(), maxAge);
        GeoCircle cell = LocationMapper.coverage(location).orElseThrow(() -> {
            log.info("UNABLE_TO_LOCATE: TEP's location has no usable cell coverage area");
            return unableToLocate();
        });
        Verification verification = LocationMapper.verify(cell, area);
        VerifyLocationResponse response = new VerifyLocationResponse(verification.result(), lastLocationTime)
                .matchRate(verification.matchRate());
        if (device.echoInResponse()) {
            response.device(new DeviceResponse().phoneNumber(device.phoneNumber()));
        }
        return ResponseEntity.ok(response);
    }

    // When the UE last reported its location, which must be no older than maxAge seconds. The spec answers an
    // unknown age with UNABLE_TO_FULFILL_MAX_AGE too, but without maxAge any age is fine, and the response still
    // needs a time: then it is this call's.
    private static OffsetDateTime lastLocationTime(OffsetDateTime lastUpdated, Integer maxAge) {
        if (maxAge == null) {
            return lastUpdated != null ? lastUpdated : OffsetDateTime.now();
        }
        if (lastUpdated == null) {
            log.info("UNABLE_TO_FULFILL_MAX_AGE: TEP gives no location time, maxAge {}s", maxAge);
            throw unableToFulfillMaxAge();
        }
        long age = Duration.between(lastUpdated, OffsetDateTime.now()).getSeconds();
        if (age > maxAge) {
            log.info("UNABLE_TO_FULFILL_MAX_AGE: the location is {}s old, maxAge {}s", age, maxAge);
            throw unableToFulfillMaxAge();
        }
        return lastUpdated;
    }

    private TepSubscriberLocation locate(String phoneNumber) {
        try {
            return subscriberApi.getSubscriberLocation(new TepDevice().phoneNumber(phoneNumber), TepCorrelator.current());
        } catch (HttpClientErrorException.NotFound e) {
            // TEP answers 404 both for an unknown subscriber and for one it can't place in a cell
            if (subscriberExists(phoneNumber)) {
                log.info("UNABLE_TO_LOCATE: TEP knows the subscriber but has no location for them");
                throw unableToLocate();
            }
            throw CamaraApiException.identifierNotFound();
        } catch (RestClientException e) {
            throw CamaraApiException.unavailable(e);
        }
    }

    private boolean subscriberExists(String phoneNumber) {
        try {
            subscriberApi.getSubscriberStatus(new TepDevice().phoneNumber(phoneNumber), TepCorrelator.current());
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            return false;
        } catch (RestClientException e) {
            throw CamaraApiException.unavailable(e);
        }
    }

    /**
     * Single place that decides which subscriber the request is about: the three-legged token's subscriber,
     * or, with a two-legged token, the device in the body.
     */
    private ResolvedDevice resolveDevice(VerifyLocationRequest request) {
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

    static CamaraApiException unableToLocate() {
        return new CamaraApiException(HttpStatus.UNPROCESSABLE_CONTENT, "LOCATION_VERIFICATION.UNABLE_TO_LOCATE",
                "The network is unable to locate the device");
    }

    static CamaraApiException unableToFulfillMaxAge() {
        return new CamaraApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "LOCATION_VERIFICATION.UNABLE_TO_FULFILL_MAX_AGE", "Unable to provide expected freshness for location");
    }

    private record ResolvedDevice(String phoneNumber, boolean echoInResponse) {
    }
}
