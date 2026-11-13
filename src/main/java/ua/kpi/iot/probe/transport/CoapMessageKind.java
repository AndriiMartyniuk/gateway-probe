package ua.kpi.iot.probe.transport;

/** What a CoAP-over-TCP message is, from the LwM2M server's point of view. */
public enum CoapMessageKind {

	/** Capabilities and Settings Message (RFC 8323 §5.3). */
	CSM, PING, PONG, RELEASE, ABORT,

	/** Device → server: Register ({@code POST /rd}). */
	REGISTER,
	/** Server → device: response to Register. */
	REGISTER_RESPONSE,
	/** Device → server: Update ({@code POST /rd/<id>}). */
	UPDATE, UPDATE_RESPONSE,
	/** Device → server: Deregister ({@code DELETE /rd/<id>}). */
	DEREGISTER, DEREGISTER_RESPONSE,

	/** Server → device: Observe ({@code GET} with Observe = 0). */
	OBSERVE,
	/** Device → server: the response to an Observe. */
	OBSERVE_RESPONSE,
	/** Device → server: a later notification of an observation. */
	NOTIFICATION,

	/** Any other request (e.g. Read, Write, Send) and its response. */
	OTHER_REQUEST, OTHER_RESPONSE

}
