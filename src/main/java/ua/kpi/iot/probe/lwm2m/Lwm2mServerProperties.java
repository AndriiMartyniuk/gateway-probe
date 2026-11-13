package ua.kpi.iot.probe.lwm2m;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.Resource;

import ua.kpi.iot.probe.transport.TransportSettings;

/**
 * Endpoints of the embedded Leshan LwM2M server. Only CoAP over TCP (RFC 8323) and CoAP over TLS are exposed; no UDP.
 */
@ConfigurationProperties("probe.lwm2m")
public record Lwm2mServerProperties(@DefaultValue Tcp tcp, @DefaultValue Tls tls,
		@DefaultValue Transport transport) {

	/**
	 * Netty settings shared by the TCP and TLS listeners (each listener gets its own thread pools).
	 *
	 * @param acceptThreads threads accepting connections
	 * @param ioThreads socket I/O and TLS handshake threads; 0 = Netty default (2 x CPUs)
	 * @param backlog accept queue length
	 * @param idleTimeout close connections idle for this long; 0 disables it
	 * @param tlsHandshakeTimeout maximum TLS handshake duration
	 */
	public record Transport(@DefaultValue("1") int acceptThreads, @DefaultValue("0") int ioThreads,
			@DefaultValue("1024") int backlog, @DefaultValue("0s") Duration idleTimeout,
			@DefaultValue("10s") Duration tlsHandshakeTimeout) {

		public TransportSettings toSettings() {
			return new TransportSettings(acceptThreads, ioThreads, backlog, idleTimeout, tlsHandshakeTimeout);
		}

	}

	/** Plain CoAP over TCP endpoint ({@code coap+tcp://}). */
	public record Tcp(@DefaultValue("true") boolean enabled, @DefaultValue("0.0.0.0") String host,
			@DefaultValue("5683") int port) {
	}

	/**
	 * CoAP over TLS endpoint ({@code coaps+tcp://}). Leshan requires mutual TLS with X.509 certificates: devices
	 * present a certificate whose CN equals their endpoint name and that is signed by a trusted CA.
	 *
	 * @param keyStore PKCS#12 key store with the server private key and certificate chain
	 * @param trustedCertificates PEM file with CA certificate(s) used to verify device certificates
	 * @param x509Endpoints endpoint names registered in the security store for X.509 authentication
	 * @param acceptCaSignedDevices also accept devices not in the security store if their certificate is signed by a
	 * trusted CA and its CN equals the endpoint name (needed for large emulated fleets)
	 */
	public record Tls(@DefaultValue("true") boolean enabled, @DefaultValue("0.0.0.0") String host,
			@DefaultValue("5684") int port, Resource keyStore, @DefaultValue("changeit") String keyStorePassword,
			@DefaultValue("server") String keyAlias, Resource trustedCertificates,
			@DefaultValue List<String> x509Endpoints, @DefaultValue("true") boolean acceptCaSignedDevices) {
	}

}
