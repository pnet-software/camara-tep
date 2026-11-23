package gr.pnet.camara.common.web;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import jakarta.servlet.http.HttpServletRequest;

/**
 * What a request ended with, beyond its HTTP status: the CAMARA error code it was answered with, the exception behind
 * it, and the API client that called. Recorded where each is known (the error handlers, the security chain) and read
 * once the request is done, by the access log and the {@code http.server.requests} metric.
 */
public final class RequestOutcome {

    static final String CODE = RequestOutcome.class.getName() + ".code";
    static final String TYPE = RequestOutcome.class.getName() + ".type";
    static final String CLIENT_ID = RequestOutcome.class.getName() + ".clientId";

    private RequestOutcome() {
    }

    /** Records the CAMARA error code the request is answered with, and the exception behind it if any */
    public static void error(HttpServletRequest request, String code, Throwable cause) {
        request.setAttribute(CODE, code);
        if (cause != null) {
            request.setAttribute(TYPE, cause.getClass().getSimpleName());
        }
    }

    /** As {@link #error(HttpServletRequest, String, Throwable)}, for the request this thread is serving, if any */
    public static void error(String code, Throwable cause) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(CODE, code, RequestAttributes.SCOPE_REQUEST);
            if (cause != null) {
                attributes.setAttribute(TYPE, cause.getClass().getSimpleName(), RequestAttributes.SCOPE_REQUEST);
            }
        }
    }

    public static void clientId(HttpServletRequest request, String clientId) {
        if (clientId != null) {
            request.setAttribute(CLIENT_ID, clientId);
        }
    }

    public static String code(HttpServletRequest request) {
        return (String) request.getAttribute(CODE);
    }

    public static String type(HttpServletRequest request) {
        return (String) request.getAttribute(TYPE);
    }

    public static String clientId(HttpServletRequest request) {
        return (String) request.getAttribute(CLIENT_ID);
    }
}
