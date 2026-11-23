package gr.pnet.camara.qualityondemand.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import gr.pnet.camara.qualityondemand.session.SessionService;

/**
 * Ends sessions whose duration is over, every {@code camara.qod.expiry-check-interval}. Every replica runs it: each
 * expired session is claimed in Redis by exactly one.
 */
@Configuration
@EnableScheduling
@ConditionalOnBooleanProperty(name = "camara.qod.expiry-enabled", matchIfMissing = true)
public class ExpiryScheduling implements SchedulingConfigurer {

    private final SessionService sessions;
    private final QodProperties properties;

    public ExpiryScheduling(SessionService sessions, QodProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.addFixedDelayTask(sessions::expireDue, properties.expiryCheckInterval());
    }
}
