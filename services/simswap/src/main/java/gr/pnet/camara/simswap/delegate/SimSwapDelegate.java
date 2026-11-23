package gr.pnet.camara.simswap.delegate;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.NativeWebRequest;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.AccessTokenSubject;
import gr.pnet.camara.simswap.generated.status.CheckSimSwapApiDelegate;
import gr.pnet.camara.simswap.generated.status.RetrieveSimSwapDateApiDelegate;
import gr.pnet.camara.simswap.generated.status.model.CheckSimSwapInfo;
import gr.pnet.camara.simswap.generated.status.model.CreateCheckSimSwap;
import gr.pnet.camara.simswap.generated.status.model.CreateSimSwapDate;
import gr.pnet.camara.simswap.generated.status.model.SimSwapInfo;
import gr.pnet.camara.tep.TepCorrelator;
import gr.pnet.tepclient.SubscribersApi;
import gr.pnet.tepclient.model.TepDevice;
import gr.pnet.tepclient.model.TepSwaps;

@Service
public class SimSwapDelegate implements RetrieveSimSwapDateApiDelegate, CheckSimSwapApiDelegate {

    private static final Logger log = LoggerFactory.getLogger(SimSwapDelegate.class);

    // The spec's default; also what an explicit "maxAge": null gets
    private static final int DEFAULT_MAX_AGE_HOURS = 240;

    private final SubscribersApi subscriberApi;
    private final AccessTokenSubject tokenSubject;

    public SimSwapDelegate(SubscribersApi subscriberApi, AccessTokenSubject tokenSubject) {
        this.subscriberApi = subscriberApi;
        this.tokenSubject = tokenSubject;
    }

    // Both generated interfaces declare this default, so Java needs it resolved here
    @Override
    public Optional<NativeWebRequest> getRequest() {
        return Optional.empty();
    }

    @Override
    public ResponseEntity<SimSwapInfo> retrieveSimSwapDate(CreateSimSwapDate request, String correlator) {
        // No change time from TEP is the spec's null: the operator can't tell the date
        return ResponseEntity.ok(new SimSwapInfo(lastSimChange(request.getPhoneNumber())));
    }

    @Override
    public ResponseEntity<CheckSimSwapInfo> checkSimSwap(CreateCheckSimSwap request, String correlator) {
        OffsetDateTime lastChange = lastSimChange(request.getPhoneNumber());
        // Without a change time there's nothing to compare maxAge with
        if (lastChange == null) {
            log.info("SERVICE_NOT_APPLICABLE: TEP has no SIM change date for the subscriber");
            throw CamaraApiException.serviceNotApplicable();
        }
        // maxAge is in hours
        int maxAge = request.getMaxAge() == null ? DEFAULT_MAX_AGE_HOURS : request.getMaxAge();
        boolean swapped = lastChange.isAfter(OffsetDateTime.now().minusHours(maxAge));
        return ResponseEntity.ok(new CheckSimSwapInfo(swapped));
    }

    // When the IMSI last changed for the phone number, i.e. the latest SIM swap; null if TEP doesn't say
    private OffsetDateTime lastSimChange(String bodyPhoneNumber) {
        String phoneNumber = resolvePhoneNumber(bodyPhoneNumber);
        TepSwaps swaps;
        try {
            // By MSISDN: a SIM swap changes the IMSI, so the old one no longer finds the subscriber
            swaps = subscriberApi.getSwaps(new TepDevice().phoneNumber(phoneNumber), TepCorrelator.current());
        } catch (HttpClientErrorException.NotFound e) {
            throw CamaraApiException.identifierNotFound();
        } catch (RestClientException e) {
            throw CamaraApiException.unavailable(e);
        }
        return swaps.getLatestSimChange();
    }

    /**
     * Single place that decides which subscriber the request is about: the three-legged token's subscriber,
     * or, with a two-legged token, the phone number in the body.
     */
    private String resolvePhoneNumber(String bodyPhoneNumber) {
        Optional<String> tokenPhoneNumber = tokenSubject.phoneNumber();
        if (tokenPhoneNumber.isPresent()) {
            // CAMARA: the body must not name a phone number the token already identifies, even the same one
            if (bodyPhoneNumber != null) {
                throw CamaraApiException.unnecessaryIdentifier();
            }
            return tokenPhoneNumber.get();
        }
        if (bodyPhoneNumber == null) {
            throw CamaraApiException.missingIdentifier();
        }
        return bodyPhoneNumber;
    }
}
