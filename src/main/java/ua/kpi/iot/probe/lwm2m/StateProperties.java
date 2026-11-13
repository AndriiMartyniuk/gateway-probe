package ua.kpi.iot.probe.lwm2m;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the server-side session state (registrations and observations) lives — the state-placement variable of
 * experiment E2.
 *
 * @param placement {@code IN_MEMORY}: Leshan's in-memory store, lost with the instance; {@code REDIS}: Leshan's
 * {@code RedisRegistrationStore} on the Redis of {@code spring.data.redis.*}, survives the instance
 */
@ConfigurationProperties("probe.state")
public record StateProperties(@DefaultValue("IN_MEMORY") Placement placement) {

	public enum Placement {

		IN_MEMORY, REDIS

	}

}
