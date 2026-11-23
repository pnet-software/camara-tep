package gr.pnet.camara.common.error;

/**
 * CAMARA Commonalities {@code ErrorInfo} body. Every API spec declares the same schema, so the shared
 * handler renders this one instead of each API's generated copy.
 */
public record ErrorInfo(int status, String code, String message) {
}
