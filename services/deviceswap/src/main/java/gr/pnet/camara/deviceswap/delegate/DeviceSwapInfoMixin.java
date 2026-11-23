package gr.pnet.camara.deviceswap.delegate;

import org.springframework.boot.jackson.JacksonMixin;

import com.fasterxml.jackson.annotation.JsonInclude;

import gr.pnet.camara.deviceswap.generated.status.model.DeviceSwapInfo;

/**
 * Leaves out a null {@code monitoredPeriod}, which is optional but not nullable. {@code latestDeviceChange} is
 * required and nullable, so a global non_null, as in roaming, would wrongly drop it.
 */
@JacksonMixin(DeviceSwapInfo.class)
abstract class DeviceSwapInfoMixin {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract Integer getMonitoredPeriod();
}
