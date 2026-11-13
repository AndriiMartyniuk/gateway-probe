package ua.kpi.iot.probe.transport;

import java.net.InetSocketAddress;

import org.eclipse.leshan.core.endpoint.Protocol;
import org.eclipse.leshan.servers.security.SecurityStore;
import org.eclipse.leshan.servers.security.ServerSecurityInfo;
import org.eclipse.leshan.transport.javacoap.identity.DefaultCoapIdentityHandler;
import org.eclipse.leshan.transport.javacoap.server.coaptcp.transport.CoapTcpTransportResolver;
import org.eclipse.leshan.transport.javacoap.server.coaptcp.transport.DefaultTransportContextMatcher;
import org.eclipse.leshan.transport.javacoap.server.endpoint.AbstractJavaCoapServerEndpointsProvider;

import com.mbed.coap.packet.BlockSize;
import com.mbed.coap.packet.CoapRequest;
import com.mbed.coap.packet.CoapResponse;
import com.mbed.coap.server.CoapServer;
import com.mbed.coap.server.TcpCoapServer;
import com.mbed.coap.server.filter.TokenGeneratorFilter;
import com.mbed.coap.server.observe.NotificationsReceiver;
import com.mbed.coap.server.observe.ObservationsStore;
import com.mbed.coap.utils.Service;

/** Leshan endpoint for plain CoAP over TCP ({@code coap+tcp://}) backed by {@link NettyCoapTcpServerTransport}. */
public class CoapTcpServerEndpointsProvider extends AbstractJavaCoapServerEndpointsProvider {

	private final TransportSettings settings;

	private final ConnectionEventListener events;

	private volatile NettyCoapTcpServerTransport transport;

	public CoapTcpServerEndpointsProvider(InetSocketAddress localAddress, TransportSettings settings,
			ConnectionEventListener events) {
		super(Protocol.COAP_TCP, "CoAP over TCP endpoint (gateway-probe Netty transport)", localAddress,
				new DefaultCoapIdentityHandler());
		this.settings = settings;
		this.events = events;
	}

	@Override
	protected CoapServer createCoapServer(InetSocketAddress localAddress, ServerSecurityInfo serverSecurityInfo,
			SecurityStore securityStore, Service<CoapRequest, CoapResponse> resources,
			NotificationsReceiver notificationReceiver, ObservationsStore observationsStore) {
		transport = new NettyCoapTcpServerTransport(localAddress, new CoapTcpTransportResolver(),
				new DefaultTransportContextMatcher(), null, settings, events);
		return TcpCoapServer.builder()
			.outboundFilter(TokenGeneratorFilter.RANDOM)
			.transport(transport)
			.blockSize(BlockSize.S_1024_BERT)
			.maxIncomingBlockTransferSize(4000)
			.maxMessageSize(2100)
			.route(resources)
			.notificationsReceiver(notificationReceiver)
			.observationsStore(observationsStore)
			.build();
	}

	/** The transport, available once the Leshan server has created its endpoints. */
	public NettyCoapTcpServerTransport transport() {
		return transport;
	}

}
