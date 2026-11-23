package gr.pnet.camara.common.security;

import org.springframework.security.web.util.UrlUtils;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Where an API's OAuth 2.0 protected resource metadata (RFC 9728) is: Spring Security's endpoint with the API's path
 * appended, e.g. {@code /.well-known/oauth-protected-resource/device-reachability-status} (RFC 9728 3.1). Every API
 * then has its own document, with its own scopes, also behind one host that serves them all. Spring Security
 * answers below its endpoint and takes the {@code resource} from the path after it.
 */
public final class ResourceMetadata {

    /** Spring Security's metadata endpoint */
    public static final String PATH = "/.well-known/oauth-protected-resource";

    /** What a service's security chain lets through without a token: the endpoint, with or without an API's path */
    public static final String PATHS = PATH + "/**";

    private ResourceMetadata() {
    }

    /**
     * The metadata URL of the API the request was for, which its 401 links to: the endpoint + the request's first
     * path segment, the API's name in every CAMARA path ({@code /device-reachability-status/v1/retrieve} gives
     * {@code /device-reachability-status}). As the client addressed the service, like Spring Security's own link.
     */
    public static String url(HttpServletRequest request) {
        String contextPath = request.getContextPath();
        String path = request.getRequestURI().substring(contextPath.length());
        int end = path.indexOf('/', 1);
        String api = end > 0 ? path.substring(0, end) : path;
        return UriComponentsBuilder.fromUriString(UrlUtils.buildFullRequestUrl(request))
                .replacePath(contextPath + PATH + (api.equals("/") ? "" : api))
                .replaceQuery(null)
                .fragment(null)
                .build()
                .toUriString();
    }
}
