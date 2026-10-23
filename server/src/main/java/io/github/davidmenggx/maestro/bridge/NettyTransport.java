package io.github.davidmenggx.maestro.bridge;

import io.github.davidmenggx.maestro.config.MaestroConfig.NodeAddress;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link Transport}. A link is two TCP connections, one per ordered channel, with 4-byte
 * little-endian length-prefixed frames. Requests travel as UDP datagrams from a per-node socket
 * that outlives its links. An answer is matched to its node by the socket it came back to, not by
 * the address it came from.
 *
 * <p>Frames are sent without copying, so a caller must not change one it has sent.
 *
 * <p>The network runs on its own thread. What it hears is queued until {@link #drain} hands it to
 * the listener on the server thread, with the time it was heard.
 */
public final class NettyTransport implements Transport, AutoCloseable {

  /**
   * Unsent bytes one connection may hold before its link is closed, so a node that stops reading
   * cannot grow them without bound.
   */
  public static final int MAX_PENDING_BYTES = 64 << 20;

  static final int CONNECT_TIMEOUT_MILLIS = 2_000;

  /** Largest frame a node sends on a connection. Its replies are a few dozen bytes. */
  static final int MAX_REPLY_BYTES = 64 << 10;

  private static final Logger LOG = LoggerFactory.getLogger("maestro");

  private static final int HEADER_BYTES = Integer.BYTES;

  /** Where one node listens. */
  private record Endpoints(
      InetSocketAddress world, InetSocketAddress control, InetSocketAddress request) {}

  /**
   * One connect attempt and, once both connections are up, the link it opened. {@code reported}:
   * its outcome was queued for the listener. {@code ended}: its connections are closed or closing.
   */
  private static final class Link {
    final NodeId node;
    io.netty.channel.Channel world;
    io.netty.channel.Channel control;
    boolean up;
    boolean reported;
    boolean ended;

    Link(NodeId node) {
      this.node = node;
    }
  }

  private final Map<NodeId, Endpoints> endpoints = new LinkedHashMap<>();
  private final int maxPendingBytes;
  private final LongSupplier nanoTime;
  private final EventLoopGroup group;

  /** Each node's request socket. */
  private final Map<NodeId, io.netty.channel.Channel> datagrams = new LinkedHashMap<>();

  private final Queue<Runnable> heard = new ConcurrentLinkedQueue<>();

  /** Guarded by {@code this}. */
  private final Map<NodeId, Link> links = new HashMap<>();

  private volatile Listener listener;

  /**
   * Resolves every node's addresses and binds its request socket. {@code nanoTime} stamps each
   * frame when heard, on the controller clock's scale. Throws {@link IllegalArgumentException} if a
   * host does not resolve or two nodes take requests at one address.
   */
  public NettyTransport(
      Map<NodeId, NodeAddress> nodes, int maxPendingBytes, LongSupplier nanoTime) {
    this.maxPendingBytes = maxPendingBytes;
    this.nanoTime = nanoTime;
    Set<InetSocketAddress> requestAddresses = new HashSet<>();
    for (Map.Entry<NodeId, NodeAddress> entry : nodes.entrySet()) {
      NodeAddress address = entry.getValue();
      Endpoints node =
          new Endpoints(
              resolve(address.host(), address.worldPort()),
              resolve(address.host(), address.controlPort()),
              resolve(address.host(), address.requestPort()));
      endpoints.put(entry.getKey(), node);
      if (!requestAddresses.add(node.request())) {
        throw new IllegalArgumentException("two nodes take requests at " + node.request());
      }
    }
    group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    try {
      for (NodeId node : endpoints.keySet()) {
        datagrams.put(
            node,
            new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                // Read a whole datagram at once. The default buffer would cut an answer short.
                .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(1 << 16))
                .handler(new Datagrams(node))
                .bind(0)
                .syncUninterruptibly()
                .channel());
      }
    } catch (RuntimeException e) {
      for (io.netty.channel.Channel bound : datagrams.values()) {
        bound.close();
      }
      group.shutdownGracefully(0, 0, TimeUnit.SECONDS);
      throw e;
    }
  }

  /** The local address of the node's request socket, which its answers come back to. */
  InetSocketAddress requestSocket(NodeId node) {
    return (InetSocketAddress) datagrams.get(node).localAddress();
  }

  private static InetSocketAddress resolve(String host, int port) {
    InetSocketAddress address = new InetSocketAddress(host, port);
    if (address.isUnresolved()) {
      throw new IllegalArgumentException("cannot resolve node host " + host);
    }
    return address;
  }

  @Override
  public void listen(Listener listener) {
    if (this.listener != null) {
      throw new IllegalStateException("the transport already has a listener");
    }
    this.listener = listener;
  }

  /** Hands what the network heard to the listener, in arrival order. Server thread only. */
  public void drain() {
    // Only this thread removes entries, so at least this many are present.
    for (int pending = heard.size(); pending > 0; pending--) {
      try {
        heard.poll().run();
      } catch (RuntimeException e) {
        LOG.error("Maestro transport callback failed", e);
      }
    }
  }

  @Override
  public void connect(NodeId node) {
    if (listener == null) {
      throw new IllegalStateException("connect before listen");
    }
    Endpoints address = endpoints.get(node);
    if (address == null) {
      throw new IllegalArgumentException(node + " is not in the fleet");
    }
    Link link = new Link(node);
    synchronized (this) {
      Link replaced = links.put(node, link);
      if (replaced != null) {
        end(replaced, false);
      }
    }
    open(link, Transport.Channel.WORLD, address.world());
    open(link, Transport.Channel.CONTROL, address.control());
  }

  /** Connects one of the attempt's connections. The second to come up opens the link. */
  private void open(Link link, Transport.Channel channel, InetSocketAddress address) {
    connector(link, channel)
        .connect(address)
        .addListener(
            (ChannelFuture done) -> {
              if (!done.isSuccess()) {
                end(link, true);
                return;
              }
              boolean opened;
              synchronized (this) {
                if (link.ended) {
                  done.channel().close();
                  return;
                }
                if (channel == Transport.Channel.WORLD) {
                  link.world = done.channel();
                } else {
                  link.control = done.channel();
                }
                opened = link.world != null && link.control != null;
                if (opened) {
                  link.up = true;
                  link.reported = true;
                }
              }
              done.channel().closeFuture().addListener(closed -> end(link, true));
              if (opened) {
                heard.add(() -> listener.onConnected(link.node));
              }
            });
  }

  private Bootstrap connector(Link link, Transport.Channel channel) {
    return new Bootstrap()
        .group(group)
        .channel(NioSocketChannel.class)
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
        .option(ChannelOption.TCP_NODELAY, true)
        .option(ChannelOption.SO_KEEPALIVE, true)
        .option(
            ChannelOption.WRITE_BUFFER_WATER_MARK,
            new WriteBufferWaterMark(maxPendingBytes / 2, maxPendingBytes))
        .handler(
            new ChannelInitializer<SocketChannel>() {
              @Override
              protected void initChannel(SocketChannel socket) {
                socket
                    .pipeline()
                    .addLast(
                        new LengthFieldBasedFrameDecoder(
                            ByteOrder.LITTLE_ENDIAN,
                            MAX_REPLY_BYTES,
                            0,
                            HEADER_BYTES,
                            0,
                            HEADER_BYTES,
                            true),
                        new Replies(link, channel));
              }
            });
  }

  /**
   * Closes the attempt's connections. Its outcome is reported if it had none, and its loss if it
   * was up and {@code lost}. A link another attempt replaced is not reported lost.
   */
  private void end(Link link, boolean lost) {
    boolean report;
    synchronized (this) {
      if (link.ended) {
        return;
      }
      link.ended = true;
      if (links.get(link.node) == link) {
        links.remove(link.node);
      }
      report = !link.reported || (link.up && lost);
      link.reported = true;
    }
    if (link.world != null) {
      link.world.close();
    }
    if (link.control != null) {
      link.control.close();
    }
    if (report) {
      heard.add(() -> listener.onDisconnected(link.node));
    }
  }

  @Override
  public void send(NodeId node, Transport.Channel channel, ByteBuffer frame) {
    Link link;
    synchronized (this) {
      link = links.get(node);
      if (link == null || !link.up || link.ended) {
        return;
      }
    }
    ByteBuf payload = Unpooled.wrappedBuffer(frame.duplicate());
    if (channel == Transport.Channel.REQUEST) {
      datagrams.get(node).writeAndFlush(new DatagramPacket(payload, endpoints.get(node).request()));
      return;
    }
    io.netty.channel.Channel connection =
        channel == Transport.Channel.WORLD ? link.world : link.control;
    ByteBuf header =
        Unpooled.buffer(HEADER_BYTES, HEADER_BYTES).writeIntLE(payload.readableBytes());
    connection.writeAndFlush(Unpooled.wrappedBuffer(header, payload));
    if (!connection.isWritable()) {
      LOG.warn(
          "Maestro: {} holds more than {} unsent bytes on its {} connection; closing its link",
          node,
          maxPendingBytes,
          channel);
      end(link, true);
    }
  }

  /** Closes every link and request socket. Nothing more is reported. */
  @Override
  public void close() {
    synchronized (this) {
      for (Link link : List.copyOf(links.values())) {
        link.reported = true;
        end(link, false);
      }
    }
    for (io.netty.channel.Channel socket : datagrams.values()) {
      socket.close().syncUninterruptibly();
    }
    group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    heard.clear();
  }

  /** Frames from one connection of a link. */
  private final class Replies extends SimpleChannelInboundHandler<ByteBuf> {

    private final Link link;
    private final Transport.Channel channel;

    Replies(Link link, Transport.Channel channel) {
      this.link = link;
      this.channel = channel;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, ByteBuf frame) {
      long arrived = nanoTime.getAsLong();
      byte[] bytes = ByteBufUtil.getBytes(frame);
      heard.add(
          () -> {
            // A frame of a link that has since ended is lost with it.
            boolean live;
            synchronized (NettyTransport.this) {
              live = !link.ended;
            }
            if (live) {
              listener.onFrame(link.node, channel, ByteBuffer.wrap(bytes), arrived);
            }
          });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
      LOG.warn("Maestro: {} connection to {} failed: {}", channel, link.node, cause.toString());
      context.close();
    }
  }

  /** Answers on one node's request socket. The controller drops answers of another run. */
  private final class Datagrams extends SimpleChannelInboundHandler<DatagramPacket> {

    private final NodeId node;

    Datagrams(NodeId node) {
      this.node = node;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
      long arrived = nanoTime.getAsLong();
      byte[] bytes = ByteBufUtil.getBytes(packet.content());
      heard.add(
          () -> listener.onFrame(node, Transport.Channel.REQUEST, ByteBuffer.wrap(bytes), arrived));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
      // An earlier send failed, as to a node that is down. The socket still works.
      LOG.debug("Maestro request socket: {}", cause.toString());
    }
  }
}
