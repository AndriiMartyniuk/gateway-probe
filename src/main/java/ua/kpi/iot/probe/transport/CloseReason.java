package ua.kpi.iot.probe.transport;

/** Why a device connection ended. */
public enum CloseReason {

	/** The device closed the connection (FIN/RST), or the network dropped it. */
	PEER_CLOSED,

	/** The TLS handshake failed or timed out; the connection never reached the CoAP layer. */
	TLS_HANDSHAKE_FAILED,

	/** No traffic for the configured idle timeout. */
	IDLE_TIMEOUT,

	/** Unexpected error in the pipeline (decode failure, I/O error). */
	ERROR,

	/** The server closed the connection on purpose (e.g. security info removed, drain). */
	SERVER_CLOSED,

	/** The transport was stopped. */
	SERVER_SHUTDOWN

}
