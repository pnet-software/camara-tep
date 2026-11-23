package gr.pnet.camara.common.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Says so at startup when the API is served without access tokens ({@code camara.auth.enabled=false}), which is
 * meant for labs only, so it can't go unnoticed in production.
 */
@Component
@ConditionalOnBooleanProperty(name = "camara.auth.enabled", havingValue = false)
public class AuthDisabledWarning {

    private static final Logger log = LoggerFactory.getLogger(AuthDisabledWarning.class);

    @EventListener(ApplicationReadyEvent.class)
    public void warn() {
        log.warn("Authentication is disabled (AUTH_ENABLED=false): the API accepts calls without an access token");
    }
}
