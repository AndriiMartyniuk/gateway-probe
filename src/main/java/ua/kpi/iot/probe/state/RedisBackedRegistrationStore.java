package ua.kpi.iot.probe.state;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.observation.ObservationIdentifier;
import org.eclipse.leshan.server.redis.serialization.ObservationSerDes;
import org.eclipse.leshan.server.redis.serialization.RegistrationSerDes;
import org.eclipse.leshan.server.registration.Deregistration;
import org.eclipse.leshan.server.registration.InMemoryRegistrationStore;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.registration.RegistrationUpdate;
import org.eclipse.leshan.server.registration.UpdatedRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Registration store for the {@code REDIS} state placement: <b>in-memory primary, asynchronous replica in Redis</b>.
 *
 * <p>
 * All reads are served from memory ({@link InMemoryRegistrationStore}), so nothing on java-coap's single dispatch
 * thread waits for Redis — Leshan's {@code RedisRegistrationStore} does blocking Redis round trips there for every
 * Register, Observe and notification, which saturated the thread under load. Every change is applied in memory first
 * and then sent to Redis asynchronously (Lettuce, one connection, so commands reach Redis in the order they were made).
 * On start the store loads the state found in Redis — how a restarted instance recovers the sessions of its devices.
 *
 * <p>
 * Consequence for the session-state model: a crash loses the changes not yet acknowledged by Redis. That window is
 * measured ({@code probe.state.redis.write.latency}, {@code probe.state.redis.write.pending}).
 *
 * <p>
 * Redis layout ({@code <prefix>} = {@code probe.state.redis.key-prefix}):
 * <ul>
 * <li>{@code <prefix>reg:<registrationId>} — registration (Leshan's JSON serialisation);</li>
 * <li>{@code <prefix>obs:<registrationId>} — hash: observation id → observation (Leshan's serialisation).</li>
 * </ul>
 */
public class RedisBackedRegistrationStore extends InMemoryRegistrationStore {

	private static final Logger log = LoggerFactory.getLogger(RedisBackedRegistrationStore.class);

	private final StatefulRedisConnection<byte[], byte[]> connection;

	private final RedisAsyncCommands<byte[], byte[]> redis;

	private final String prefix;

	private final boolean loadOnStart;

	private final RegistrationSerDes registrationSerDes = new RegistrationSerDes();

	private final ObservationSerDes observationSerDes = new ObservationSerDes();

	private final ObjectMapper json = new ObjectMapper();

	private final AtomicInteger pending = new AtomicInteger();

	private final Timer writeLatency;

	private final MeterRegistry meters;

	public RedisBackedRegistrationStore(StatefulRedisConnection<byte[], byte[]> connection, String keyPrefix,
			boolean loadOnStart, MeterRegistry meters) {
		this.connection = connection;
		this.redis = connection.async();
		this.prefix = keyPrefix;
		this.loadOnStart = loadOnStart;
		this.meters = meters;
		this.writeLatency = Timer.builder("probe.state.redis.write.latency")
			.description("Time from a state change in memory to its acknowledgement by Redis (replication lag)")
			.publishPercentileHistogram()
			.register(meters);
		Gauge.builder("probe.state.redis.write.pending", pending, AtomicInteger::get)
			.description("State changes sent to Redis and not yet acknowledged")
			.register(meters);
		for (String operation : new String[] { "register", "update", "deregister", "replace", "observe",
				"observe-replace", "observe-cancel", "observe-cancel-all" }) {
			failureCounter(operation);
		}
	}

	private Counter failureCounter(String operation) {
		return Counter.builder("probe.state.redis.write.failures").tag("operation", operation).register(meters);
	}

	// ---- lifecycle: recover from Redis, then start the expiration cleaner ----------------------------------------

	@Override
	public synchronized void start() {
		if (loadOnStart) {
			load();
		}
		super.start();
	}

	/** Loads all registrations and observations found in Redis into memory (synchronously; startup only). */
	public synchronized int load() {
		RedisCommands<byte[], byte[]> sync = connection.sync();
		ScanArgs args = ScanArgs.Builder.matches(prefix + "reg:*").limit(1000);
		int registrations = 0;
		int observations = 0;
		KeyScanCursor<byte[]> cursor = sync.scan(args);
		while (true) {
			for (byte[] key : cursor.getKeys()) {
				byte[] value = sync.get(key);
				if (value == null) {
					continue;
				}
				Registration registration = deserializeRegistration(value);
				if (registration == null) {
					continue;
				}
				super.addRegistration(registration);
				registrations++;
				Map<byte[], byte[]> stored = sync.hgetall(observationsKey(registration.getId()));
				for (byte[] bytes : stored.values()) {
					Observation observation = observationSerDes.deserialize(bytes);
					super.addObservation(registration.getId(), observation, false);
					observations++;
				}
			}
			if (cursor.isFinished()) {
				break;
			}
			cursor = sync.scan(ScanCursor.of(cursor.getCursor()), args);
		}
		log.info("Loaded {} registrations and {} observations from Redis ({}*)", registrations, observations, prefix);
		return registrations;
	}

	// ---- registrations -----------------------------------------------------------------------------------------

	@Override
	public synchronized Deregistration addRegistration(Registration registration) {
		Deregistration replaced = super.addRegistration(registration);
		if (replaced != null) {
			deleteSession(replaced.getRegistration().getId(), "replace");
		}
		putRegistration(registration, "register");
		return replaced;
	}

	@Override
	public synchronized UpdatedRegistration updateRegistration(RegistrationUpdate update) {
		UpdatedRegistration updated = super.updateRegistration(update);
		if (updated != null) {
			putRegistration(updated.getUpdatedRegistration(), "update");
		}
		return updated;
	}

	/** Also reached by the expiration cleaner of the in-memory store, so expiries are replicated too. */
	@Override
	public synchronized Deregistration removeRegistration(String registrationId) {
		Deregistration removed = super.removeRegistration(registrationId);
		if (removed != null) {
			deleteSession(registrationId, "deregister");
		}
		return removed;
	}

	// ---- observations ------------------------------------------------------------------------------------------

	@Override
	public synchronized Collection<Observation> addObservation(String registrationId, Observation observation,
			boolean addIfAbsent) {
		Collection<Observation> removed = super.addObservation(registrationId, observation, addIfAbsent);
		for (Observation old : removed) {
			track(redis.hdel(observationsKey(registrationId), observationField(old.getId())), "observe-replace");
		}
		track(redis.hset(observationsKey(registrationId), observationField(observation.getId()),
				observationSerDes.serialize(observation)), "observe");
		return removed;
	}

	@Override
	public synchronized Observation removeObservation(String registrationId, ObservationIdentifier observationId) {
		Observation removed = super.removeObservation(registrationId, observationId);
		if (removed != null) {
			track(redis.hdel(observationsKey(registrationId), observationField(observationId)), "observe-cancel");
		}
		return removed;
	}

	@Override
	public synchronized Collection<Observation> removeObservations(String registrationId) {
		Collection<Observation> removed = super.removeObservations(registrationId);
		track(redis.del(observationsKey(registrationId)), "observe-cancel-all");
		return removed;
	}

	// ---- Redis writes ------------------------------------------------------------------------------------------

	private void putRegistration(Registration registration, String operation) {
		track(redis.set(registrationKey(registration.getId()), registrationSerDes.bSerialize(registration)),
				operation);
	}

	private void deleteSession(String registrationId, String operation) {
		track(redis.del(registrationKey(registrationId), observationsKey(registrationId)), operation);
	}

	/** Counts the write as pending until Redis acknowledges it, records the lag, and logs failures. */
	private void track(CompletionStage<?> write, String operation) {
		long started = System.nanoTime();
		pending.incrementAndGet();
		write.whenComplete((result, error) -> {
			pending.decrementAndGet();
			if (error == null) {
				writeLatency.record(Duration.ofNanos(System.nanoTime() - started));
			}
			else {
				failureCounter(operation).increment();
				log.warn("Redis write failed ({}): {}", operation, error.toString());
			}
		});
	}

	private Registration deserializeRegistration(byte[] value) {
		try {
			return registrationSerDes.deserialize(json.readTree(value));
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Skipping unreadable registration in Redis: {}", ex.toString());
			return null;
		}
	}

	private byte[] registrationKey(String registrationId) {
		return (prefix + "reg:" + registrationId).getBytes(StandardCharsets.UTF_8);
	}

	private byte[] observationsKey(String registrationId) {
		return (prefix + "obs:" + registrationId).getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] observationField(ObservationIdentifier id) {
		return id.toString().getBytes(StandardCharsets.UTF_8);
	}

}
