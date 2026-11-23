package gr.pnet.camara.qosprofiles.delegate;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import gr.pnet.camara.common.error.CamaraApiException;
import gr.pnet.camara.common.security.AccessTokenSubject;
import gr.pnet.camara.qosprofiles.generated.status.QoSProfilesApiDelegate;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfile;
import gr.pnet.camara.qosprofiles.generated.status.model.QosProfileDeviceRequest;
import gr.pnet.camara.tep.TepCorrelator;
import gr.pnet.tepclient.QualityProfilesApi;
import gr.pnet.tepclient.model.TepQualityProfile;
import gr.pnet.tepclient.model.TepQualityProfilePage;

@Service
public class QosProfilesDelegate implements QoSProfilesApiDelegate {

    // The largest page TEP gives
    static final int PAGE_SIZE = 500;

    private final QualityProfilesApi qualityProfileApi;
    private final AccessTokenSubject tokenSubject;

    public QosProfilesDelegate(QualityProfilesApi qualityProfileApi, AccessTokenSubject tokenSubject) {
        this.qualityProfileApi = qualityProfileApi;
        this.tokenSubject = tokenSubject;
    }

    @Override
    public ResponseEntity<List<QosProfile>> retrieveQoSProfiles(QosProfileDeviceRequest request, String correlator) {
        // CAMARA: the body must not name a device the token already identifies, even the same one
        if (tokenSubject.phoneNumber().isPresent() && request.getDevice() != null) {
            throw CamaraApiException.unnecessaryIdentifier();
        }
        // Every profile is available to every device, so a device filters nothing out
        return ResponseEntity.ok(allProfiles().stream()
                .map(QosProfileMapper::toQosProfile)
                .filter(profile -> request.getName() == null || request.getName().equals(profile.getName()))
                .filter(profile -> request.getStatus() == null || request.getStatus() == profile.getStatus())
                .toList());
    }

    @Override
    public ResponseEntity<QosProfile> getQosProfile(String name, String correlator) {
        return ResponseEntity.ok(QosProfileMapper.toQosProfile(profileNamed(name)));
    }

    private TepQualityProfile profileNamed(String name) {
        try {
            return qualityProfileApi.getQualityProfile(name, TepCorrelator.current());
        } catch (HttpClientErrorException.NotFound e) {
            throw CamaraApiException.notFound();
        } catch (RestClientException e) {
            throw CamaraApiException.unavailable(e);
        }
    }

    // TEP gives the list a page at a time; CAMARA returns it whole
    private List<TepQualityProfile> allProfiles() {
        List<TepQualityProfile> profiles = new ArrayList<>();
        try {
            TepQualityProfilePage page;
            do {
                page = qualityProfileApi.listQualityProfiles(TepCorrelator.current(), PAGE_SIZE, profiles.size());
                profiles.addAll(page.getItems());
            } while (!page.getItems().isEmpty() && profiles.size() < page.getTotal());
        } catch (RestClientException e) {
            throw CamaraApiException.unavailable(e);
        }
        return profiles;
    }
}
