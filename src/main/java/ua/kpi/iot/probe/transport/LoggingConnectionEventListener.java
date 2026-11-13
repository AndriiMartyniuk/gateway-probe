package ua.kpi.iot.probe.transport;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default listener: logs connection events at DEBUG (handshake failures at INFO). */
public class LoggingConnectionEventListener implements ConnectionEventListener {

	private static final Logger log = LoggerFactory.getLogger(LoggingConnectionEventListener.class);

	@Override
	public void onAccepted(ConnectionInfo connection) {
		log.debug("[{}] accepted from {} tls={}", connection.id(), connection.remoteAddress(), connection.tls());
	}

	@Override
	public void onTlsHandshake(ConnectionInfo connection, Duration duration, boolean resumed, Throwable failure) {
		if (failure == null) {
			log.debug("[{}] TLS handshake ok in {} ms resumed={}", connection.id(), duration.toMillis(), resumed);
		}
		else {
			log.info("[{}] TLS handshake failed after {} ms from {}: {}", connection.id(), duration.toMillis(),
					connection.remoteAddress(), failure.toString());
		}
	}

	@Override
	public void onEstablished(ConnectionInfo connection, Duration sinceAccept) {
		log.debug("[{}] established {} ms after accept", connection.id(), sinceAccept.toMillis());
	}

	@Override
	public void onClosed(ConnectionInfo connection, CloseReason reason, Duration lifetime) {
		log.debug("[{}] closed reason={} after {} ms", connection.id(), reason, lifetime.toMillis());
	}

}
