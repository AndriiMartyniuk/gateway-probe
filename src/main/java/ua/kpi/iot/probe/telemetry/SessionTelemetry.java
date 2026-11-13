package ua.kpi.iot.probe.telemetry;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.eclipse.leshan.core.observation.CompositeObservation;
import org.eclipse.leshan.core.observation.Observation;
import org.eclipse.leshan.core.observation.SingleObservation;
import org.eclipse.leshan.core.response.ObserveCompositeResponse;
import org.eclipse.leshan.core.response.ObserveResponse;
import org.eclipse.leshan.server.observation.ObservationListener;
import org.eclipse.leshan.server.registration.Registration;
import org.eclipse.leshan.server.registration.RegistrationListener;
import org.eclipse.leshan.server.registration.RegistrationUpdate;

import ua.kpi.iot.probe.transport.CloseReason;
import ua.kpi.iot.probe.transport.ConnectionEventListener;

/**
 * Turns transport events (per connection) and Leshan events (per registration) into one per-session view, and
 * publishes it to {@link ProbeMetrics} and the {@link SessionEventLog}.
 *
 * <p>
 * Connections and registrations are joined by the device's remote address, which both the transport and Leshan see.
 * This gives the registration latency measured from TLS completion on the same connection.
 */
public class SessionTelemetry implements ConnectionEventListener, RegistrationListener, ObservationListener {

	private final ProbeMetrics metrics;

	private final SessionEventLog eventLog;

	/** Open connections by remote address. */
	private final Map<InetSocketAddress, Connection> connections = new ConcurrentHashMap<>();

	public SessionTelemetry(ProbeMetrics metrics, SessionEventLog eventLog) {
		this.metrics = metrics;
		this.eventLog = eventLog;
	}

	private static final class Connection {

		final String id;

		final String transport;

		volatile long establishedAt;

		volatile String endpoint;

		Connection(String id, String transport) {
			this.id = id;
			this.transport = transport;
		}

	}

	// ---- transport ---------------------------------------------------------------------------------------------

	@Override
	public void onAccepted(ConnectionInfo c) {
		String transport = transport(c);
		if (c.remoteAddress() != null) {
			connections.put(c.remoteAddress(), new Connection(c.id(), transport));
		}
		metrics.accepted(transport);
		log(SessionEvent.Type.ACCEPTED, c.id(), null, transport, null, null, null, null, null, null);
	}

	@Override
	public void onTlsHandshake(ConnectionInfo c, Duration duration, boolean resumed, Throwable failure) {
		metrics.tlsHandshake(duration, resumed, failure == null);
		log(SessionEvent.Type.TLS_HANDSHAKE, c.id(), null, transport(c), millis(duration), resumed,
				failure == null ? null : failure.toString(), null, null, null);
	}

	@Override
	public void onEstablished(ConnectionInfo c, Duration sinceAccept) {
		Connection connection = find(c.remoteAddress());
		if (connection != null) {
			connection.establishedAt = System.nanoTime();
			metrics.established(transport(c));
		}
		log(SessionEvent.Type.ESTABLISHED, c.id(), null, transport(c), millis(sinceAccept), null, null, null, null,
				null);
	}

	@Override
	public void onClosed(ConnectionInfo c, CloseReason reason, Duration lifetime) {
		Connection connection = c.remoteAddress() != null ? connections.remove(c.remoteAddress()) : null;
		boolean wasEstablished = connection != null && connection.establishedAt != 0;
		metrics.closed(transport(c), reason.name(), wasEstablished, lifetime);
		log(SessionEvent.Type.CLOSED, c.id(), connection != null ? connection.endpoint : null, transport(c),
				millis(lifetime), null, reason.name(), null, null, null);
	}

	// ---- LwM2M registrations -----------------------------------------------------------------------------------

	@Override
	public void registered(Registration registration, Registration previousReg,
			Collection<Observation> previousObservations) {
		metrics.registration("registered");
		Connection connection = find(registration.getSocketAddress());
		Double sinceEstablished = null;
		if (connection != null) {
			connection.endpoint = registration.getEndpoint();
			if (connection.establishedAt != 0) {
				Duration latency = Duration.ofNanos(System.nanoTime() - connection.establishedAt);
				metrics.registerLatency(connection.transport, latency);
				sinceEstablished = millis(latency);
			}
		}
		log(SessionEvent.Type.REGISTERED, connection != null ? connection.id : null, registration.getEndpoint(),
				connection != null ? connection.transport : null, sinceEstablished, null, null, registration.getId(),
				null, previousObservations != null && !previousObservations.isEmpty() ? previousObservations.size()
						: null);
	}

	@Override
	public void updated(RegistrationUpdate update, Registration updatedReg, Registration previousReg) {
		metrics.registration("updated");
		Connection connection = find(updatedReg.getSocketAddress());
		log(SessionEvent.Type.UPDATED, connection != null ? connection.id : null, updatedReg.getEndpoint(), null, null,
				null, null, updatedReg.getId(), null, null);
	}

	@Override
	public void unregistered(Registration registration, Collection<Observation> observations, boolean expired,
			Registration newReg) {
		String reason = newReg != null ? "replaced" : expired ? "expired" : "deregistered";
		metrics.registration(reason);
		Connection connection = find(registration.getSocketAddress());
		log(SessionEvent.Type.UNREGISTERED, connection != null ? connection.id : null, registration.getEndpoint(),
				null, null, null, reason, registration.getId(), null, observations != null ? observations.size() : 0);
	}

	// ---- LwM2M observations ------------------------------------------------------------------------------------

	@Override
	public void newObservation(Observation observation, Registration registration) {
		metrics.observation("created");
		logObservation(SessionEvent.Type.OBSERVATION_CREATED, observation, registration, null);
	}

	@Override
	public void cancelled(Observation observation) {
		metrics.observation("cancelled");
		logObservation(SessionEvent.Type.OBSERVATION_CANCELLED, observation, null, null);
	}

	@Override
	public void onResponse(SingleObservation observation, Registration registration, ObserveResponse response) {
		notification(observation, registration);
	}

	@Override
	public void onResponse(CompositeObservation observation, Registration registration,
			ObserveCompositeResponse response) {
		notification(observation, registration);
	}

	@Override
	public void onError(Observation observation, Registration registration, Exception error) {
		metrics.observation("error");
		logObservation(SessionEvent.Type.OBSERVATION_ERROR, observation, registration, error.toString());
	}

	private void notification(Observation observation, Registration registration) {
		metrics.notification();
		if (eventLog.logsNotifications()) {
			logObservation(SessionEvent.Type.NOTIFICATION, observation, registration, null);
		}
	}

	private void logObservation(String type, Observation observation, Registration registration, String error) {
		Connection connection = registration != null ? find(registration.getSocketAddress()) : null;
		log(type, connection != null ? connection.id : null, registration != null ? registration.getEndpoint() : null,
				null, null, null, error, observation.getRegistrationId(), path(observation), null);
	}

	// ---- helpers -----------------------------------------------------------------------------------------------

	private Connection find(InetSocketAddress address) {
		return address != null ? connections.get(address) : null;
	}

	private void log(String type, String conn, String endpoint, String transport, Double ms, Boolean resumed,
			String reason, String regId, String path, Integer observations) {
		eventLog.append(new SessionEvent(nowMicros(), type, conn, endpoint, transport, ms, resumed, reason, regId, path,
				observations));
	}

	private static String path(Observation observation) {
		if (observation instanceof SingleObservation single) {
			return single.getPath().toString();
		}
		if (observation instanceof CompositeObservation composite) {
			return composite.getPaths().stream().map(Object::toString).collect(Collectors.joining(","));
		}
		return null;
	}

	private static String transport(ConnectionInfo c) {
		return c.tls() ? "tls" : "tcp";
	}

	private static Double millis(Duration duration) {
		return duration.toNanos() / 1_000_000.0;
	}

	private static long nowMicros() {
		Instant now = Instant.now();
		return now.getEpochSecond() * 1_000_000L + now.getNano() / 1_000;
	}

}
