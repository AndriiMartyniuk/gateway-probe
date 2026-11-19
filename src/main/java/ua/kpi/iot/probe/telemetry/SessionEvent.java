package ua.kpi.iot.probe.telemetry;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One line of the per-session event log. Absent fields are omitted from the JSON.
 *
 * @param t wall-clock time, microseconds since the Unix epoch
 * @param event event type, see {@link Type}
 * @param conn connection id (Netty channel id)
 * @param ep LwM2M endpoint name, once known
 * @param transport {@code tcp} or {@code tls}
 * @param ms duration in milliseconds measured with a monotonic clock; meaning depends on the event
 * @param resumed TLS session resumption (for {@code tls_handshake})
 * @param reason close reason or failure cause
 * @param regId Leshan registration id
 * @param path observed LwM2M path(s)
 * @param observations number of observations affected (e.g. dropped with a registration)
 * @param seq sample sequence number (Probe Sensor notifications)
 * @param bytesIn wire bytes received on the connection so far ({@code tls_handshake}, {@code closed})
 * @param bytesOut wire bytes sent on the connection so far
 * @param flights TLS handshake flights (changes of direction until the handshake completed)
 * @param dir {@code in} (device to server) or {@code out}, for {@code coap_message}
 * @param kind CoAP message kind, for {@code coap_message}
 * @param bytes serialised CoAP-over-TCP size of the message, for {@code coap_message}
 * @param token CoAP token (hex), for {@code coap_message}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionEvent(long t, String event, String conn, String ep, String transport, Double ms, Boolean resumed,
		String reason, String regId, String path, Integer observations, Long seq, Long bytesIn, Long bytesOut,
		Integer flights, String dir, String kind, Integer bytes, String token) {

	/** Event types written to the log. */
	public static final class Type {

		/** TCP accepted. */
		public static final String ACCEPTED = "accepted";

		/** TLS handshake finished; {@code ms} = since accept, {@code reason} set on failure. */
		public static final String TLS_HANDSHAKE = "tls_handshake";

		/**
		 * TCP accepted from the address of a connection whose close is not processed yet; {@code reason} = id of
		 * that previous connection, {@code ep} = its endpoint.
		 */
		public static final String ADDRESS_REUSED = "address_reused";

		/** Ready for CoAP; {@code ms} = since accept. */
		public static final String ESTABLISHED = "established";

		/** Connection closed; {@code ms} = lifetime. */
		public static final String CLOSED = "closed";

		/** LwM2M Register accepted; {@code ms} = since the connection was established. */
		public static final String REGISTERED = "registered";

		/** LwM2M Update. */
		public static final String UPDATED = "updated";

		/** Registration removed; {@code reason} = deregistered | expired | replaced. */
		public static final String UNREGISTERED = "unregistered";

		/** Server sent Observe (after Register). */
		public static final String OBSERVE_SENT = "observe_sent";

		/** Observe answered; {@code ms} = since observe_sent, {@code reason} set on failure. */
		public static final String OBSERVE_DONE = "observe_done";

		/** Observation established. */
		public static final String OBSERVATION_CREATED = "observation_created";

		/** Observation cancelled. */
		public static final String OBSERVATION_CANCELLED = "observation_cancelled";

		/** Observation failed. */
		public static final String OBSERVATION_ERROR = "observation_error";

		/** One CoAP message (only with {@code probe.event-log.coap-messages=true}): {@code dir}, {@code kind}, {@code bytes}. */
		public static final String COAP_MESSAGE = "coap_message";

		/**
		 * Notification received (only when enabled); for Probe Sensor: {@code seq}, {@code ms} = data age (server
		 * receive time - device sample time, wall clocks), {@code reason} = gap | duplicate when out of sequence.
		 */
		public static final String NOTIFICATION = "notification";

		private Type() {
		}

	}

}
