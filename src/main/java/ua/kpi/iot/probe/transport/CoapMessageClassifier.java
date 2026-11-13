package ua.kpi.iot.probe.transport;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.mbed.coap.packet.CoapPacket;
import com.mbed.coap.packet.CoapTcpPacketSerializer;
import com.mbed.coap.packet.Code;
import com.mbed.coap.packet.Method;
import com.mbed.coap.packet.Opaque;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * Sits between the CoAP codec and the dispatcher: classifies every CoAP message in both directions
 * ({@link CoapMessageKind}) and reports it with its serialised size (CoAP-over-TCP framing, without TLS record
 * overhead). Responses are matched to their requests by token. Runs on the channel's I/O thread only.
 */
final class CoapMessageClassifier extends ChannelDuplexHandler {

	private final ConnectionEventListener events;

	private final Function<Channel, ConnectionEventListener.ConnectionInfo> info;

	/** Requests from the device awaiting the server's response, by token. */
	private final Map<Opaque, CoapMessageKind> inboundRequests = new HashMap<>();

	/** Observe requests from the server awaiting the device's response. */
	private final Set<Opaque> pendingObserves = new HashSet<>();

	CoapMessageClassifier(ConnectionEventListener events,
			Function<Channel, ConnectionEventListener.ConnectionInfo> info) {
		this.events = events;
		this.info = info;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof CoapPacket packet) {
			report(ctx, ConnectionEventListener.Direction.IN, classifyInbound(packet), packet);
		}
		super.channelRead(ctx, msg);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		if (msg instanceof CoapPacket packet) {
			report(ctx, ConnectionEventListener.Direction.OUT, classifyOutbound(packet), packet);
		}
		super.write(ctx, msg, promise);
	}

	private CoapMessageKind classifyInbound(CoapPacket packet) {
		CoapMessageKind signal = signal(packet.getCode());
		if (signal != null) {
			return signal;
		}
		if (packet.getMethod() != null) {
			String path = packet.headers().getUriPath();
			CoapMessageKind kind;
			if (packet.getMethod() == Method.POST && "/rd".equals(path)) {
				kind = CoapMessageKind.REGISTER;
			}
			else if (packet.getMethod() == Method.POST && path != null && path.startsWith("/rd/")) {
				kind = CoapMessageKind.UPDATE;
			}
			else if (packet.getMethod() == Method.DELETE && path != null && path.startsWith("/rd/")) {
				kind = CoapMessageKind.DEREGISTER;
			}
			else {
				kind = CoapMessageKind.OTHER_REQUEST;
			}
			inboundRequests.put(packet.getToken(), kind);
			return kind;
		}
		if (pendingObserves.remove(packet.getToken())) {
			return CoapMessageKind.OBSERVE_RESPONSE;
		}
		return packet.headers().getObserve() != null ? CoapMessageKind.NOTIFICATION : CoapMessageKind.OTHER_RESPONSE;
	}

	private CoapMessageKind classifyOutbound(CoapPacket packet) {
		CoapMessageKind signal = signal(packet.getCode());
		if (signal != null) {
			return signal;
		}
		if (packet.getMethod() != null) {
			Integer observe = packet.headers().getObserve();
			if (packet.getMethod() == Method.GET && observe != null && observe == 0) {
				pendingObserves.add(packet.getToken());
				return CoapMessageKind.OBSERVE;
			}
			return CoapMessageKind.OTHER_REQUEST;
		}
		CoapMessageKind request = inboundRequests.remove(packet.getToken());
		if (request == null) {
			return CoapMessageKind.OTHER_RESPONSE;
		}
		return switch (request) {
			case REGISTER -> CoapMessageKind.REGISTER_RESPONSE;
			case UPDATE -> CoapMessageKind.UPDATE_RESPONSE;
			case DEREGISTER -> CoapMessageKind.DEREGISTER_RESPONSE;
			default -> CoapMessageKind.OTHER_RESPONSE;
		};
	}

	private static CoapMessageKind signal(Code code) {
		if (code == null || !code.isSignaling()) {
			return null;
		}
		return switch (code) {
			case C701_CSM -> CoapMessageKind.CSM;
			case C702_PING -> CoapMessageKind.PING;
			case C703_PONG -> CoapMessageKind.PONG;
			case C704_RELEASE -> CoapMessageKind.RELEASE;
			default -> CoapMessageKind.ABORT;
		};
	}

	private void report(ChannelHandlerContext ctx, ConnectionEventListener.Direction direction, CoapMessageKind kind,
			CoapPacket packet) {
		int size;
		try {
			size = CoapTcpPacketSerializer.serialize(packet).length;
		}
		catch (Exception ex) {
			size = -1;
		}
		events.onCoapMessage(info.apply(ctx.channel()), direction, kind, size);
	}

}
