package ua.kpi.iot.probe;

import org.springframework.test.context.TestPropertySource;

/** All application tests again, with java-coap messages dispatched per connection on virtual threads. */
@TestPropertySource(properties = "probe.lwm2m.transport.dispatch=VIRTUAL")
class VirtualDispatchTests extends GatewayProbeApplicationTests {

}
