package ua.kpi.iot.probe.transport;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.eclipse.leshan.transport.javacoap.server.coaptcp.transport.CoapTcpDecoder;
import org.eclipse.leshan.transport.javacoap.server.coaptcp.transport.CoapTcpEncoder;
import org.eclipse.leshan.transport.javacoap.server.coaptcp.transport.TransportContextHandler;
import org.eclipse.leshan.transport.javacoap.server.coaptcp.transport.UnconnectedPeerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mbed.coap.packet.CoapPacket;
import com.mbed.coap.server.messaging.CoapTcpDispatcher;
import com.mbed.coap.transport.CoapTcpListener;
import com.mbed.coap.transport.CoapTcpTransport;
import com.mbed.coap.transport.TransportContext;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * CoAP over TCP / TLS (RFC 8323) server transport for java-coap, replacing Leshan's {@code NettyCoapTcpTransport}.
 *
 * <p>
 * Differences from the Leshan transport:
 * <ul>
 * <li>socket I/O and TLS handshakes run on a configurable pool of Netty threads (Leshan: one thread);</li>
 * <li>decoded packets are queued and handed to java-coap by a single dispatch thread, so packets arriving on
 * different I/O threads are never dropped (java-coap's {@code CoapServer} consumes one packet at a time);</li>
 * <li>connection lifecycle events (accept, TLS handshake with duration and resumption, close with reason) are
 * reported to a {@link ConnectionEventListener};</li>
 * <li>configurable backlog, idle timeout and handshake timeout; event-loop threads are shut down on stop.</li>
 * </ul>
 */
public class NettyCoapTcpServerTransport implements CoapTcpTransport {

	private static final Logger log = LoggerFactory.getLogger(NettyCoapTcpServerTransport.class);

	private static final AttributeKey<Long> ACCEPTED_AT = AttributeKey.newInstance("probe.acceptedAt");

	private static final AttributeKey<CloseReason> CLOSE_REASON = AttributeKey.newInstance("probe.closeReason");

	private final InetSocketAddress bindAddress;

	private final Function<Channel, TransportContext> contextResolver;

	private final BiFunction<TransportContext, TransportContext, Boolean> contextMatcher;

	private final SslContext sslContext;

	private final TransportSettings settings;

	private final ConnectionEventListener events;

	private final ConcurrentMap<SocketAddress, Channel> activeChannels = new ConcurrentHashMap<>();

	private final BlockingQueue<CoapPacket> inbound = new LinkedBlockingQueue<>();

	private volatile CoapTcpListener coapListener;

	private volatile Channel serverChannel;

	private EventLoopGroup acceptGroup;

	private EventLoopGroup ioGroup;

	private ExecutorService dispatcher;

	/** {@link DispatchMode#VIRTUAL}: one virtual thread per busy connection. */
	private ExecutorService virtualDispatcher;

	private static final AttributeKey<ConnectionMailbox> MAILBOX = AttributeKey.newInstance("probe.mailbox");

	/** {@link DispatchMode#VIRTUAL}: tasks waiting in all connection mailboxes. */
	private final AtomicInteger mailboxQueued = new AtomicInteger();

	/** Never completed: in VIRTUAL mode java-coap's own receive loop is not used. */
	private final CompletableFuture<CoapPacket> idleReceive = new CompletableFuture<>();

	/**
	 * @param sslContext server TLS context, or {@code null} for plain CoAP over TCP
	 */
	public NettyCoapTcpServerTransport(InetSocketAddress bindAddress, Function<Channel, TransportContext> contextResolver,
			BiFunction<TransportContext, TransportContext, Boolean> contextMatcher, SslContext sslContext,
			TransportSettings settings, ConnectionEventListener events) {
		this.bindAddress = bindAddress;
		this.contextResolver = contextResolver;
		this.contextMatcher = contextMatcher;
		this.sslContext = sslContext;
		this.settings = settings;
		this.events = events;
	}

	@Override
	public synchronized void start() throws IOException {
		String name = sslContext != null ? "coaps-tcp" : "coap-tcp";
		acceptGroup = new MultiThreadIoEventLoopGroup(settings.acceptThreads(),
				new DefaultThreadFactory(name + "-accept"), NioIoHandler.newFactory());
		ioGroup = new MultiThreadIoEventLoopGroup(settings.ioThreads(), new DefaultThreadFactory(name + "-io"),
				NioIoHandler.newFactory());
		if (settings.dispatch() == DispatchMode.VIRTUAL) {
			virtualDispatcher = Executors
				.newThreadPerTaskExecutor(Thread.ofVirtual().name(name + "-vdispatch-", 0).factory());
		}
		else {
			dispatcher = Executors.newSingleThreadExecutor(Thread.ofPlatform().name(name + "-dispatch").factory());
		}

		ServerBootstrap bootstrap = new ServerBootstrap().group(acceptGroup, ioGroup)
			.channel(NioServerSocketChannel.class)
			.option(ChannelOption.SO_BACKLOG, settings.backlog())
			.childOption(ChannelOption.SO_KEEPALIVE, true)
			.childOption(ChannelOption.TCP_NODELAY, true)
			.childHandler(new PipelineInitializer());
		try {
			serverChannel = bootstrap.bind(bindAddress).sync().channel();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while binding " + bindAddress, ex);
		}
		catch (Exception ex) {
			shutdownThreads();
			throw new IOException("Unable to bind " + bindAddress, ex);
		}
	}

	@Override
	public synchronized void stop() {
		if (serverChannel != null) {
			serverChannel.close().syncUninterruptibly();
		}
		activeChannels.values().forEach(channel -> closeWith(channel, CloseReason.SERVER_SHUTDOWN));
		shutdownThreads();
	}

	private void shutdownThreads() {
		if (acceptGroup != null) {
			acceptGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
		}
		if (ioGroup != null) {
			ioGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
		}
		if (dispatcher != null) {
			// interrupts the pending receive(); java-coap's CoapServer is already stopped at this point
			dispatcher.shutdownNow();
		}
		if (virtualDispatcher != null) {
			virtualDispatcher.shutdownNow();
			idleReceive.completeExceptionally(new IllegalStateException("Transport stopped"));
		}
	}

	@Override
	public CompletableFuture<CoapPacket> receive() {
		if (settings.dispatch() == DispatchMode.VIRTUAL) {
			// packets go straight to java-coap's dispatcher from the connection mailboxes
			return idleReceive;
		}
		// Always completed on the dispatch thread: never re-enters java-coap on an I/O thread and keeps the
		// receive -> handle -> receive loop iterative instead of recursive.
		try {
			return CompletableFuture.supplyAsync(() -> {
				try {
					return inbound.take();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Transport stopped", ex);
				}
			}, dispatcher);
		}
		catch (RejectedExecutionException ex) {
			return CompletableFuture.failedFuture(new IllegalStateException("Transport stopped", ex));
		}
	}

	@Override
	public CompletableFuture<Boolean> sendPacket(CoapPacket packet) {
		Channel channel = activeChannels.get(packet.getRemoteAddress());
		if (channel == null) {
			return CompletableFuture.failedFuture(
					new UnconnectedPeerException("Peer " + packet.getRemoteAddress() + " is not connected"));
		}
		CompletableFuture<Boolean> result = new CompletableFuture<>();
		channel.writeAndFlush(packet).addListener(future -> {
			if (future.isSuccess()) {
				result.complete(true);
			}
			else {
				result.completeExceptionally(future.cause());
			}
		});
		return result;
	}

	@Override
	public InetSocketAddress getLocalSocketAddress() {
		return (InetSocketAddress) serverChannel.localAddress();
	}

	@Override
	public void setListener(CoapTcpListener listener) {
		this.coapListener = listener;
	}

	/** Number of connections that completed accept (and TLS handshake, if any) and are still open. */
	public int activeConnections() {
		return activeChannels.size();
	}

	/** Packets decoded but not yet handed to java-coap (single queue, or all connection mailboxes). */
	public int inboundQueueSize() {
		return settings.dispatch() == DispatchMode.VIRTUAL ? mailboxQueued.get() : inbound.size();
	}

	/**
	 * Closes matching connections. For TLS the session is invalidated first so the device cannot resume it.
	 */
	public void closeConnections(Predicate<Channel> filter) {
		for (Channel channel : activeChannels.values()) {
			if (filter.test(channel)) {
				SslHandler ssl = channel.pipeline().get(SslHandler.class);
				if (ssl != null) {
					ssl.engine().getSession().invalidate();
				}
				closeWith(channel, CloseReason.SERVER_CLOSED);
			}
		}
	}

	private static void closeWith(Channel channel, CloseReason reason) {
		channel.attr(CLOSE_REASON).setIfAbsent(reason);
		channel.close();
	}

	private static ConnectionEventListener.ConnectionInfo info(Channel channel) {
		TrafficCounter traffic = channel.attr(TrafficCounter.KEY).get();
		return new ConnectionEventListener.ConnectionInfo(channel.id().asShortText(),
				(InetSocketAddress) channel.remoteAddress(), channel.pipeline().get(SslHandler.class) != null,
				traffic != null ? traffic.snapshot() : ConnectionEventListener.Traffic.NONE);
	}

	private static Duration sinceAccept(Channel channel) {
		Long acceptedAt = channel.attr(ACCEPTED_AT).get();
		return acceptedAt == null ? Duration.ZERO : Duration.ofNanos(System.nanoTime() - acceptedAt);
	}

	private final class PipelineInitializer extends ChannelInitializer<SocketChannel> {

		@Override
		protected void initChannel(SocketChannel ch) {
			ch.pipeline().addLast("traffic", new TrafficCounter());
			if (sslContext != null) {
				SslHandler ssl = sslContext.newHandler(ch.alloc());
				ssl.setHandshakeTimeoutMillis(settings.tlsHandshakeTimeout().toMillis());
				ch.pipeline().addLast("tls", ssl);
			}
			ch.pipeline().addLast("lifecycle", new ConnectionLifecycleHandler());
			ch.pipeline().addLast("context", new TransportContextHandler(contextResolver));
			ch.pipeline().addLast("registry", new ChannelRegistryHandler());
			if (!settings.idleTimeout().isZero()) {
				ch.pipeline()
					.addLast("idle", new IdleStateHandler(0, 0, settings.idleTimeout().toMillis(), TimeUnit.MILLISECONDS));
			}
			ch.pipeline().addLast("decoder", new CoapTcpDecoder());
			ch.pipeline().addLast("encoder", new CoapTcpEncoder(contextMatcher));
			ch.pipeline().addLast("classifier", new CoapMessageClassifier(events, NettyCoapTcpServerTransport::info));
			ch.pipeline().addLast("dispatch", new DispatchHandler());
			ch.pipeline().addLast("errors", new CloseOnErrorHandler());
		}

	}

	/**
	 * Tracks accept time, TLS handshake and close. For TLS it holds back {@code channelActive} until the handshake
	 * succeeds, so the CoAP layer only sees authenticated connections.
	 */
	private final class ConnectionLifecycleHandler extends ChannelInboundHandlerAdapter {

		private long handshakeStartedAt;

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			ctx.channel().attr(ACCEPTED_AT).set(System.nanoTime());
			handshakeStartedAt = System.currentTimeMillis();
			if (sslContext == null) {
				// no handshake on plain TCP: nothing to count as flights
				TrafficCounter traffic = ctx.channel().attr(TrafficCounter.KEY).get();
				if (traffic != null) {
					traffic.handshakeDone();
				}
			}
			events.onAccepted(info(ctx.channel()));
			if (sslContext == null) {
				events.onEstablished(info(ctx.channel()), Duration.ZERO);
				super.channelActive(ctx);
			}
		}

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
			if (evt instanceof SslHandshakeCompletionEvent handshake) {
				Channel channel = ctx.channel();
				TrafficCounter traffic = channel.attr(TrafficCounter.KEY).get();
				if (traffic != null) {
					traffic.handshakeDone();
				}
				if (handshake.isSuccess()) {
					SSLSession session = channel.pipeline().get(SslHandler.class).engine().getSession();
					// A resumed session keeps the creation time of the original full handshake.
					boolean resumed = session.getCreationTime() < handshakeStartedAt;
					// read the clock once: for TLS, "handshake done" and "established" are the same instant
					Duration sinceAccept = sinceAccept(channel);
					events.onTlsHandshake(info(channel), sinceAccept, resumed, null);
					events.onEstablished(info(channel), sinceAccept);
					super.channelActive(ctx);
				}
				else {
					channel.attr(CLOSE_REASON).setIfAbsent(CloseReason.TLS_HANDSHAKE_FAILED);
					events.onTlsHandshake(info(channel), sinceAccept(channel), false, handshake.cause());
					channel.close();
				}
				return;
			}
			super.userEventTriggered(ctx, evt);
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			CloseReason reason = ctx.channel().attr(CLOSE_REASON).get();
			events.onClosed(info(ctx.channel()), reason != null ? reason : CloseReason.PEER_CLOSED,
					sinceAccept(ctx.channel()));
			super.channelInactive(ctx);
		}

	}

	private final class ChannelRegistryHandler extends ChannelInboundHandlerAdapter {

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			activeChannels.put(ctx.channel().remoteAddress(), ctx.channel());
			super.channelActive(ctx);
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			// remove by value: remoteAddress() can be null once the socket is gone (netty/netty#8501)
			activeChannels.values().remove(ctx.channel());
			super.channelInactive(ctx);
		}

	}

	private final class DispatchHandler extends ChannelInboundHandlerAdapter {

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			CoapPacket packet = (CoapPacket) msg;
			if (settings.dispatch() == DispatchMode.VIRTUAL) {
				// java-coap hands its dispatcher to the transport as the TCP listener (CoapServerBuilderForTcp)
				if (coapListener instanceof CoapTcpDispatcher dispatcher) {
					mailbox(ctx.channel()).offer(() -> dispatcher.handle(packet));
				}
				else {
					log.warn("No java-coap dispatcher registered; dropping {}", packet);
				}
			}
			else {
				inbound.add(packet);
			}
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) throws Exception {
			CoapTcpListener listener = coapListener;
			if (settings.dispatch() == DispatchMode.VIRTUAL) {
				ctx.channel().attr(MAILBOX).set(new ConnectionMailbox(virtualDispatcher, mailboxQueued));
			}
			if (listener != null && ctx.channel().remoteAddress() != null) {
				listener.onConnected((InetSocketAddress) ctx.channel().remoteAddress());
			}
			super.channelActive(ctx);
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) throws Exception {
			CoapTcpListener listener = coapListener;
			// only connections that reached the CoAP layer were announced as connected
			if (listener != null && ctx.channel().remoteAddress() != null
					&& ctx.channel().attr(TransportContextHandler.TRANSPORT_CONTEXT_ATTR).get() != null) {
				InetSocketAddress remote = (InetSocketAddress) ctx.channel().remoteAddress();
				// java-coap identifies a TCP peer by its address only. Under load the close of a dead connection can
				// be processed after a new connection from the same address (port reused by the client after a
				// reset) became active; telling java-coap then would fail the exchanges of the live connection.
				Channel current = activeChannels.get(remote);
				if (current != null && current != ctx.channel()) {
					log.debug("Close of {} superseded by a newer connection from the same address", remote);
					super.channelInactive(ctx);
					return;
				}
				ConnectionMailbox mailbox = ctx.channel().attr(MAILBOX).get();
				if (mailbox != null) {
					// after the messages of this connection that are still queued
					mailbox.offer(() -> listener.onDisconnected(remote));
				}
				else {
					listener.onDisconnected(remote);
				}
			}
			super.channelInactive(ctx);
		}

		private ConnectionMailbox mailbox(Channel channel) {
			ConnectionMailbox mailbox = channel.attr(MAILBOX).get();
			if (mailbox == null) {
				mailbox = new ConnectionMailbox(virtualDispatcher, mailboxQueued);
				ConnectionMailbox existing = channel.attr(MAILBOX).setIfAbsent(mailbox);
				if (existing != null) {
					mailbox = existing;
				}
			}
			return mailbox;
		}

	}

	private static final class CloseOnErrorHandler extends ChannelInboundHandlerAdapter {

		@Override
		public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
			if (evt instanceof IdleStateEvent) {
				closeWith(ctx.channel(), CloseReason.IDLE_TIMEOUT);
				return;
			}
			super.userEventTriggered(ctx, evt);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			boolean established = ctx.channel().attr(TransportContextHandler.TRANSPORT_CONTEXT_ATTR).get() != null;
			if (!established && (cause instanceof SSLException || cause.getCause() instanceof SSLException)) {
				// handshake failures are reported by ConnectionLifecycleHandler
				log.debug("TLS handshake error from {}: {}", ctx.channel().remoteAddress(), cause.toString());
				closeWith(ctx.channel(), CloseReason.TLS_HANDSHAKE_FAILED);
			}
			else {
				log.warn("Closing connection from {} after error", ctx.channel().remoteAddress(), cause);
				closeWith(ctx.channel(), CloseReason.ERROR);
			}
		}

	}

}
