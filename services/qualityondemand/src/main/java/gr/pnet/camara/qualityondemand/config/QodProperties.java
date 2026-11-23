package gr.pnet.camara.qualityondemand.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * @param scsAsId the SCS/AS id TEP AS sessions with QoS are created under
 * @param notificationDestination where TEP notifies this service of a session the network ended (TEP appends
 *        {@code /notifications})
 * @param apiRoot this API's public root, the {@code source} of the CloudEvents sent to a sink
 * @param expiryCheckInterval how often expired sessions are looked for
 * @param expiryRetryDelay when an expired session whose TEP delete failed is tried again
 * @param terminatedRetention how long a session that ended is still answered, as {@code UNAVAILABLE}
 * @param sinkTimeout connect and read timeout of a notification to a sink
 */
@Validated
@ConfigurationProperties("camara.qod")
public record QodProperties(
        @NotBlank String scsAsId,
        @NotNull URI notificationDestination,
        @NotNull URI apiRoot,
        @NotNull Duration expiryCheckInterval,
        @NotNull Duration expiryRetryDelay,
        @NotNull Duration terminatedRetention,
        @NotNull Duration sinkTimeout) {
}
