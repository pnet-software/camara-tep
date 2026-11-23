package gr.pnet.camara.simswap.delegate;

import org.springframework.boot.jackson.JacksonMixin;

import com.fasterxml.jackson.annotation.JsonInclude;

import gr.pnet.camara.simswap.generated.status.model.SimSwapInfo;

/**
 * Leaves out a null {@code monitoredPeriod}, which is optional but not nullable. {@code latestSimChange} is
 * required and nullable, so a global non_null, as in roaming, would wrongly drop it.
 */
@JacksonMixin(SimSwapInfo.class)
abstract class SimSwapInfoMixin {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract Integer getMonitoredPeriod();
}
