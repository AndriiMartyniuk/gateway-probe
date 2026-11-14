package ua.kpi.iot.probe.transport;

/** How decoded CoAP messages are handed to java-coap. */
public enum DispatchMode {

	/**
	 * java-coap's own receive loop: one dispatch thread per listener processes all messages of all connections, one
	 * after another (what Leshan does). Anything blocking in message processing delays every device.
	 */
	SINGLE,

	/**
	 * One mailbox per connection, drained by a virtual thread: messages of one connection are processed in order,
	 * different connections in parallel. Blocking work (e.g. a Redis round trip) parks only that connection's virtual
	 * thread.
	 */
	VIRTUAL

}
