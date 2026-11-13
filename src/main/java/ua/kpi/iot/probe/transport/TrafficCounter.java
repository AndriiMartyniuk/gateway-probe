package ua.kpi.iot.probe.transport;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;

/**
 * First handler of the pipeline (below TLS): counts the bytes on the wire and, until the TLS handshake completes, the
 * number of flights (changes of direction between reads and writes). Runs on the channel's I/O thread only.
 */
final class TrafficCounter extends ChannelDuplexHandler {

	static final AttributeKey<TrafficCounter> KEY = AttributeKey.newInstance("probe.traffic");

	private enum Direction {
		NONE, IN, OUT
	}

	private long bytesIn;

	private long bytesOut;

	private int flights;

	private boolean countingFlights = true;

	private Direction last = Direction.NONE;

	@Override
	public void handlerAdded(ChannelHandlerContext ctx) {
		ctx.channel().attr(KEY).set(this);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		long size = size(msg);
		if (size > 0) {
			bytesIn += size;
			flight(Direction.IN);
		}
		super.channelRead(ctx, msg);
	}

	@Override
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		long size = size(msg);
		if (size > 0) {
			bytesOut += size;
			flight(Direction.OUT);
		}
		super.write(ctx, msg, promise);
	}

	/** Stops counting flights (called when the TLS handshake completes). */
	void handshakeDone() {
		countingFlights = false;
	}

	ConnectionEventListener.Traffic snapshot() {
		return new ConnectionEventListener.Traffic(bytesIn, bytesOut, flights);
	}

	private void flight(Direction direction) {
		if (countingFlights && direction != last) {
			flights++;
			last = direction;
		}
	}

	private static long size(Object msg) {
		if (msg instanceof ByteBuf buf) {
			return buf.readableBytes();
		}
		if (msg instanceof ByteBufHolder holder) {
			return holder.content().readableBytes();
		}
		return 0;
	}

}
