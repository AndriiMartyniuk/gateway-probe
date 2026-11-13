package ua.kpi.iot.probe.transport;

import java.time.Duration;

/**
 * Netty settings of one CoAP-over-TCP/TLS listener.
 *
 * @param acceptThreads threads accepting TCP connections (Netty boss group)
 * @param ioThreads threads doing socket I/O and TLS handshakes (Netty worker group); 0 = Netty default (2 x CPUs)
 * @param backlog accept queue length ({@code SO_BACKLOG})
 * @param idleTimeout close a connection after this period without reads or writes; zero disables it
 * @param tlsHandshakeTimeout fail a TLS handshake that takes longer than this
 */
public record TransportSettings(int acceptThreads, int ioThreads, int backlog, Duration idleTimeout,
		Duration tlsHandshakeTimeout) {

	public TransportSettings {
		if (acceptThreads < 1) {
			throw new IllegalArgumentException("acceptThreads must be >= 1");
		}
		if (ioThreads < 0) {
			throw new IllegalArgumentException("ioThreads must be >= 0");
		}
		if (backlog < 1) {
			throw new IllegalArgumentException("backlog must be >= 1");
		}
	}

	public static TransportSettings defaults() {
		return new TransportSettings(1, 0, 1024, Duration.ZERO, Duration.ofSeconds(10));
	}

}
