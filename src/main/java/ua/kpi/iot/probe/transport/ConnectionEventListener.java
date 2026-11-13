package ua.kpi.iot.probe.transport;

import java.net.InetSocketAddress;
import java.time.Duration;

/**
 * Connection lifecycle events emitted by {@link NettyCoapTcpServerTransport}. Called on Netty I/O threads:
 * implementations must be fast and non-blocking.
 */
public interface ConnectionEventListener {

	/** TCP connection accepted. For TLS, the handshake has not started yet. */
	default void onAccepted(ConnectionInfo connection) {
	}

	/**
	 * TLS handshake finished.
	 * @param duration time from TCP accept to handshake completion
	 * @param resumed {@code true} if an existing TLS session was resumed (abbreviated handshake)
	 * @param failure the cause if the handshake failed, otherwise {@code null}
	 */
	default void onTlsHandshake(ConnectionInfo connection, Duration duration, boolean resumed, Throwable failure) {
	}

	/** Connection is ready for CoAP traffic (immediately after accept for TCP, after the handshake for TLS). */
	default void onEstablished(ConnectionInfo connection) {
	}

	/**
	 * Connection closed.
	 * @param lifetime time from TCP accept to close
	 */
	default void onClosed(ConnectionInfo connection, CloseReason reason, Duration lifetime) {
	}

	/**
	 * Identity of a connection.
	 * @param id Netty channel id (short form), unique per connection
	 * @param remoteAddress device address; may be {@code null} if the socket was already gone
	 * @param tls whether the connection is TLS
	 */
	record ConnectionInfo(String id, InetSocketAddress remoteAddress, boolean tls) {
	}

	ConnectionEventListener NO_OP = new ConnectionEventListener() {
	};

}
