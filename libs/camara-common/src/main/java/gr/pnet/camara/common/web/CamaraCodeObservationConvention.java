package gr.pnet.camara.common.web;

import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

/**
 * Tags {@code http.server.requests} with the CAMARA error code the request was answered with, {@code none} for a
 * success, so errors can be counted and alerted on by code. The codes are a short fixed list, so the tag stays low
 * in cardinality.
 */
@Component
public class CamaraCodeObservationConvention extends DefaultServerRequestObservationConvention {

    static final String CODE_TAG = "code";
    static final String NONE = "none";

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        String code = context.getCarrier() == null ? null : RequestOutcome.code(context.getCarrier());
        return super.getLowCardinalityKeyValues(context).and(KeyValue.of(CODE_TAG, code == null ? NONE : code));
    }
}
