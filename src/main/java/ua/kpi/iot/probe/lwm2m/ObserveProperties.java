package ua.kpi.iot.probe.lwm2m;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Observations the server establishes itself after every registration. Over TCP an observation lives only as long as
 * its connection (RFC 8323), so the server must re-create them after each reconnect.
 *
 * @param enabled observe after Register
 * @param objectIds objects whose instances (as announced in the registration) are observed, one Observe per instance
 * @param timeout response timeout of one Observe request
 */
@ConfigurationProperties("probe.observe")
public record ObserveProperties(@DefaultValue("true") boolean enabled, @DefaultValue("32700") List<Integer> objectIds,
		@DefaultValue("10s") Duration timeout) {
}
