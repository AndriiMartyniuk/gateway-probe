package ua.kpi.iot.probe.lwm2m;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the server-side session state (registrations and observations) lives — the state-placement variable of
 * experiment E2.
 *
 * @param placement {@code IN_MEMORY}: Leshan's in-memory store, lost with the instance; {@code REDIS}: Leshan's
 * {@code RedisRegistrationStore} (Jedis, synchronous: every read and write goes to Redis); {@code REDIS_CACHED}:
 * in-memory primary with an asynchronous replica in Redis ({@code RedisBackedRegistrationStore}, Lettuce), recovered
 * on start. Both Redis variants use the Redis of {@code spring.data.redis.*}
 * @param redis options of the Redis placement
 */
@ConfigurationProperties("probe.state")
public record StateProperties(@DefaultValue("IN_MEMORY") Placement placement, @DefaultValue Redis redis) {

	/**
	 * @param keyPrefix {@code REDIS_CACHED}: prefix of all keys written by the store
	 * @param loadOnStart {@code REDIS_CACHED}: load registrations and observations from Redis when the server starts
	 * @param poolSize {@code REDIS}: maximum Jedis connections; with {@code VIRTUAL} dispatch this bounds how many
	 * connections can wait on Redis at the same time
	 */
	public record Redis(@DefaultValue("probe:") String keyPrefix, @DefaultValue("true") boolean loadOnStart,
			@DefaultValue("64") int poolSize) {
	}

	public enum Placement {

		IN_MEMORY, REDIS, REDIS_CACHED

	}

}
