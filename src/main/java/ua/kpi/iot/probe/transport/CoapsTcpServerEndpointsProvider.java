package ua.kpi.iot.probe.transport;

import java.net.InetSocketAddress;
import java.security.Principal;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.SSLException;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.security.auth.x500.X500Principal;

import org.eclipse.leshan.core.endpoint.Protocol;
import org.eclipse.leshan.core.security.certificate.util.X509CertUtil;
import org.eclipse.leshan.core.security.certificate.verifier.DefaultCertificateVerifier;
import org.eclipse.leshan.core.security.jsse.LwM2mX509TrustManager;
import org.eclipse.leshan.servers.security.EditableSecurityStore;
import org.eclipse.leshan.servers.security.SecurityInfo;
import org.eclipse.leshan.servers.security.SecurityStore;
import org.eclipse.leshan.servers.security.SecurityStoreListener;
import org.eclipse.leshan.servers.security.ServerSecurityInfo;
import org.eclipse.leshan.transport.javacoap.SingleX509KeyManager;
import org.eclipse.leshan.transport.javacoap.identity.DefaultTlsIdentityHandler;
import org.eclipse.leshan.transport.javacoap.identity.TlsTransportContextKeys;
import org.eclipse.leshan.transport.javacoap.server.coaptcp.endpoint.LwM2mTransportContextMatcher;
import org.eclipse.leshan.transport.javacoap.server.coaptcp.transport.CoapsTcpTransportResolver;
import org.eclipse.leshan.transport.javacoap.server.coaptcp.transport.TransportContextHandler;
import org.eclipse.leshan.transport.javacoap.server.endpoint.AbstractJavaCoapServerEndpointsProvider;

import com.mbed.coap.packet.BlockSize;
import com.mbed.coap.packet.CoapRequest;
import com.mbed.coap.packet.CoapResponse;
import com.mbed.coap.server.CoapServer;
import com.mbed.coap.server.TcpCoapServer;
import com.mbed.coap.server.filter.TokenGeneratorFilter;
import com.mbed.coap.server.observe.NotificationsReceiver;
import com.mbed.coap.server.observe.ObservationsStore;
import com.mbed.coap.transport.TransportContext;
import com.mbed.coap.utils.Service;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * Leshan endpoint for CoAP over TLS ({@code coaps+tcp://}) backed by {@link NettyCoapTcpServerTransport}. Mutual TLS
 * 1.2 with X.509 certificates, as in Leshan's own provider.
 */
public class CoapsTcpServerEndpointsProvider extends AbstractJavaCoapServerEndpointsProvider {

	private final TransportSettings settings;

	private final ConnectionEventListener events;

	private volatile NettyCoapTcpServerTransport transport;

	public CoapsTcpServerEndpointsProvider(InetSocketAddress localAddress, TransportSettings settings,
			ConnectionEventListener events) {
		super(Protocol.COAPS_TCP, "CoAP over TLS endpoint (gateway-probe Netty transport)", localAddress,
				new DefaultTlsIdentityHandler());
		this.settings = settings;
		this.events = events;
	}

	@Override
	protected CoapServer createCoapServer(InetSocketAddress localAddress, ServerSecurityInfo serverSecurityInfo,
			SecurityStore securityStore, Service<CoapRequest, CoapResponse> resources,
			NotificationsReceiver notificationReceiver, ObservationsStore observationsStore) {
		transport = new NettyCoapTcpServerTransport(localAddress, new CoapsTcpTransportResolver(),
				new LwM2mTransportContextMatcher(), sslContext(serverSecurityInfo), settings, events);
		closeConnectionsOnSecurityInfoRemoval(transport, securityStore);
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

	private static SslContext sslContext(ServerSecurityInfo serverSecurityInfo) {
		if (serverSecurityInfo == null || serverSecurityInfo.getPrivateKey() == null) {
			throw new IllegalStateException("CoAP over TLS needs a server private key and certificate chain");
		}
		try {
			X509KeyManager keys = new SingleX509KeyManager(serverSecurityInfo.getPrivateKey(),
					serverSecurityInfo.getCertificateChain());
			X509TrustManager trust = new LwM2mX509TrustManager(new DefaultCertificateVerifier(
					Arrays.asList(X509CertUtil.asX509Certificates(serverSecurityInfo.getTrustedCertificates()))) {
				@Override
				protected void validateSubject(InetSocketAddress peerSocket, X509Certificate receivedCertificate) {
					// devices are identified by certificate CN in the security store, not by host name
				}
			});
			return SslContextBuilder.forServer(keys)
				.trustManager(trust)
				.protocols("TLSv1.2")
				.clientAuth(ClientAuth.REQUIRE)
				.build();
		}
		catch (SSLException | CertificateException ex) {
			throw new IllegalStateException("Unable to create TLS context", ex);
		}
	}

	/** Same behaviour as Leshan: when a device's security info is removed, drop its TLS connections. */
	private static void closeConnectionsOnSecurityInfoRemoval(NettyCoapTcpServerTransport transport,
			SecurityStore securityStore) {
		if (!(securityStore instanceof EditableSecurityStore editable)) {
			return;
		}
		editable.addListener(new SecurityStoreListener() {
			@Override
			public void securityInfoRemoved(boolean infosAreCompromised, SecurityInfo... infos) {
				transport.closeConnections(channel -> {
					TransportContext context = channel.attr(TransportContextHandler.TRANSPORT_CONTEXT_ATTR).get();
					Principal principal = context != null ? context.get(TlsTransportContextKeys.PRINCIPAL) : null;
					if (!(principal instanceof X500Principal)) {
						return false;
					}
					String commonName = X509CertUtil.extractCN(principal.getName());
					return Arrays.stream(infos)
						.anyMatch(info -> info != null && info.useX509Cert() && commonName.equals(info.getEndpoint()));
				});
			}
		});
	}

}
