package gr.pnet.camara.tep;

import org.slf4j.MDC;

import gr.pnet.camara.common.web.CorrelatorFilter;

/**
 * The x-correlator to pass to a TEP operation: the current request's, which {@link CorrelatorFilter} puts in the MDC
 * (the caller's, or a generated one). Null outside a request, and then no header is sent. The 3GPP operations, which
 * don't declare the header, get the same value from the client's interceptor.
 */
public final class TepCorrelator {

    private TepCorrelator() {
    }

    public static String current() {
        return MDC.get(CorrelatorFilter.MDC_KEY);
    }
}
