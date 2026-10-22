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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link Transport}. A link is two TCP connections, one per ordered channel, whose
 * frames each carry a 4-byte little-endian length. Requests travel as UDP datagrams from one socket
 * shared by every link, so a datagram in flight when a link changes still arrives, and an answer is
 * matched to its node by the address it came from.
 *
 * <p>The network runs on one thread of its own. What it hears waits in a queue until {@link #drain}
 * hands it to the listener on the server thread.
 */
public final class NettyTransport implements Transport, AutoCloseable {

  /**
   * Unsent bytes one connection may hold before its link is closed. A node whose connection stays
   * up while it stops reading would otherwise grow them without bound. Once it reconnects, its
   * stream resumes or its region is sent again.
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
   * One connect attempt and, once both connections are up, the link it opened. {@code reported}
   * says whether the attempt's outcome was queued for the listener, and {@code ended} whether its
   * connections are closed or closing.
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
  private final Map<InetSocketAddress, NodeId> byRequestAddress = new HashMap<>();
  private final int maxPendingBytes;
  private final EventLoopGroup group;
  private final io.netty.channel.Channel datagrams;
  private final Queue<Runnable> heard = new ConcurrentLinkedQueue<>();

  /** Guarded by {@code this}. */
  private final Map<NodeId, Link> links = new HashMap<>();

  private volatile Listener listener;

  /**
   * Resolves every node's addresses and binds the request socket. Throws {@link
   * IllegalArgumentException} if a host does not resolve.
   */
  public NettyTransport(Map<NodeId, NodeAddress> nodes, int maxPendingBytes) {
    this.maxPendingBytes = maxPendingBytes;
    for (Map.Entry<NodeId, NodeAddress> entry : nodes.entrySet()) {
      NodeAddress address = entry.getValue();
      Endpoints node =
          new Endpoints(
              resolve(address.host(), address.worldPort()),
              resolve(address.host(), address.controlPort()),
              resolve(address.host(), address.requestPort()));
      endpoints.put(entry.getKey(), node);
      if (byRequestAddress.put(node.request(), entry.getKey()) != null) {
        throw new IllegalArgumentException("two nodes take requests at " + node.request());
      }
    }
    group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    try {
      datagrams =
          new Bootstrap()
              .group(group)
              .channel(NioDatagramChannel.class)
              // Read a whole datagram at once. The default buffer would cut an answer short.
              .option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(1 << 16))
              .handler(new Datagrams())
              .bind(0)
              .syncUninterruptibly()
              .channel();
    } catch (RuntimeException e) {
      group.shutdownGracefully(0, 0, TimeUnit.SECONDS);
      throw e;
    }
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
    byte[] bytes = new byte[frame.remaining()];
    frame.duplicate().get(bytes);
    Link link;
    synchronized (this) {
      link = links.get(node);
      if (link == null || !link.up || link.ended) {
        return;
      }
    }
    if (channel == Transport.Channel.REQUEST) {
      datagrams.writeAndFlush(
          new DatagramPacket(Unpooled.wrappedBuffer(bytes), endpoints.get(node).request()));
      return;
    }
    io.netty.channel.Channel connection =
        channel == Transport.Channel.WORLD ? link.world : link.control;
    ByteBuf framed = connection.alloc().buffer(HEADER_BYTES + bytes.length);
    framed.writeIntLE(bytes.length);
    framed.writeBytes(bytes);
    connection.writeAndFlush(framed);
    if (!connection.isWritable()) {
      LOG.warn(
          "Maestro: {} holds more than {} unsent bytes on its {} connection; closing its link",
          node,
          maxPendingBytes,
          channel);
      end(link, true);
    }
  }

  /** Closes every link and the request socket. Nothing more is reported. */
  @Override
  public void close() {
    synchronized (this) {
      for (Link link : List.copyOf(links.values())) {
        link.reported = true;
        end(link, false);
      }
    }
    datagrams.close().syncUninterruptibly();
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
      byte[] bytes = ByteBufUtil.getBytes(frame);
      heard.add(
          () -> {
            // A frame of a link that has since ended is lost with it.
            boolean live;
            synchronized (NettyTransport.this) {
              live = !link.ended;
            }
            if (live) {
              listener.onFrame(link.node, channel, ByteBuffer.wrap(bytes));
            }
          });
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
      LOG.warn("Maestro: {} connection to {} failed: {}", channel, link.node, cause.toString());
      context.close();
    }
  }

  /** Answers on the request socket, from whichever node sent them. */
  private final class Datagrams extends SimpleChannelInboundHandler<DatagramPacket> {

    @Override
    protected void channelRead0(ChannelHandlerContext context, DatagramPacket packet) {
      NodeId node = byRequestAddress.get(packet.sender());
      if (node == null) {
        return;
      }
      byte[] bytes = ByteBufUtil.getBytes(packet.content());
      heard.add(() -> listener.onFrame(node, Transport.Channel.REQUEST, ByteBuffer.wrap(bytes)));
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext context, Throwable cause) {
      // An earlier send failed, as to a node that is down. The socket still works.
      LOG.debug("Maestro request socket: {}", cause.toString());
    }
  }
}
