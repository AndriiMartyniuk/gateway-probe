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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SessionEvent(long t, String event, String conn, String ep, String transport, Double ms, Boolean resumed,
		String reason, String regId, String path, Integer observations) {

	/** Event types written to the log. */
	public static final class Type {

		/** TCP accepted. */
		public static final String ACCEPTED = "accepted";

		/** TLS handshake finished; {@code ms} = since accept, {@code reason} set on failure. */
		public static final String TLS_HANDSHAKE = "tls_handshake";

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

		/** Observation established. */
		public static final String OBSERVATION_CREATED = "observation_created";

		/** Observation cancelled. */
		public static final String OBSERVATION_CANCELLED = "observation_cancelled";

		/** Observation failed. */
		public static final String OBSERVATION_ERROR = "observation_error";

		/** Notification received (only when enabled). */
		public static final String NOTIFICATION = "notification";

		private Type() {
		}

	}

}
