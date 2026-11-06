package io.github.davidmenggx.maestro.bridge;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.config.MaestroConfig.NodeAddress;
import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.Transport;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import io.netty.channel.socket.DatagramChannelConfig;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class NettyTransportTest {

  private static final NodeId NODE = NodeId.of(0);
  private static final long WAIT_MILLIS = 5_000;

  private final List<AutoCloseable> closing = new ArrayList<>();

  @AfterEach
  void closeEverything() throws Exception {
    for (AutoCloseable resource : closing.reversed()) {
      resource.close();
    }
  }

  @Test
  void aLinkIsReportedUpOnlyOnceBothConnectionsAreAndOnlyWhenDrained() throws Exception {
    FakeNode node = fakeNode();
    NettyTransport transport = transport(node.address(), NettyTransport.MAX_PENDING_BYTES);
    Recorder heard = listen(transport);

    transport.connect(NODE);
    Socket world = node.world.accept();
    assertEquals(0, heard.connected, "reported before the control connection was up");
    Socket control = node.control.accept();
    closing.add(world);
    closing.add(control);

    awaitHeard(transport, () -> heard.connected == 1);
    assertEquals(0, heard.disconnected);
  }

  @Test
  void streamFramesCarryALittleEndianLengthBothWays() throws Exception {
    FakeNode node = fakeNode();
    NettyTransport transport = transport(node.address(), NettyTransport.MAX_PENDING_BYTES);
    Recorder heard = listen(transport);
    Link link = up(transport, node, heard);

    // Only what remains of the buffer is sent, and the buffer is left as it was.
    ByteBuffer world = ByteBuffer.wrap(new byte[] {0, 1, 2, 3, 9}, 1, 3);
    transport.send(NODE, Channel.WORLD, world);
    transport.send(NODE, Channel.CONTROL, ByteBuffer.wrap(new byte[] {4}));
    assertEquals(1, world.position());
    assertEquals(3, world.remaining());
    assertArrayEquals(new byte[] {1, 2, 3}, readFrame(link.world.getInputStream()));
    assertArrayEquals(new byte[] {4}, readFrame(link.control.getInputStream()));

    writeFrame(link.control.getOutputStream(), new byte[] {7, 8});
    awaitHeard(transport, () -> heard.frames.size() == 1);
    assertEquals(Channel.CONTROL, heard.channels.get(0));
    assertArrayEquals(new byte[] {7, 8}, heard.frames.get(0));
  }

  @Test
  void requestsAreDatagramsAndAnAnswerComesBackToItsNodesSocket() throws Exception {
    FakeNode node = fakeNode();
    NettyTransport transport = transport(node.address(), NettyTransport.MAX_PENDING_BYTES);
    Recorder heard = listen(transport);
    up(transport, node, heard);

    transport.send(NODE, Channel.REQUEST, ByteBuffer.wrap(new byte[] {9, 9, 9}));
    DatagramPacket request = new DatagramPacket(new byte[64], 64);
    node.requests.receive(request);
    assertEquals(3, request.getLength());
    assertEquals(transport.requestSocket(NODE).getPort(), request.getPort());

    byte[] answer = new byte[20_000];
    answer[0] = 5;
    node.requests.send(new DatagramPacket(answer, answer.length, request.getSocketAddress()));

    awaitHeard(transport, () -> heard.frames.size() == 1);
    assertEquals(Channel.REQUEST, heard.channels.get(0));
    assertEquals(NODE, heard.nodes.get(0));
    assertArrayEquals(answer, heard.frames.get(0), "a large answer arrives whole");
  }

  @Test
  void aFrameCarriesTheTimeItWasHeardNotTheTimeItWasHandedOver() throws Exception {
    FakeNode node = fakeNode();
    AtomicLong now = new AtomicLong(7);
    AtomicInteger stamps = new AtomicInteger();
    NettyTransport transport =
        transport(
            node.address(),
            NettyTransport.MAX_PENDING_BYTES,
            () -> {
              stamps.incrementAndGet();
              return now.get();
            });
    Recorder heard = listen(transport);

    try (DatagramSocket sender = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
      sender.send(new DatagramPacket(new byte[] {1}, 1, transport.requestSocket(NODE)));
    }
    // The server stalls after the network heard the datagram, before it is handed over.
    long deadline = System.nanoTime() + WAIT_MILLIS * 1_000_000;
    while (stamps.get() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    now.set(99);

    awaitHeard(transport, () -> heard.frames.size() == 1);
    assertEquals(List.of(7L), heard.arrived);
  }

  @Test
  void anAnswerFromAnotherAddressOfTheNodeIsStillItsNodes() throws Exception {
    // As from a node behind NAT, or one with several addresses that answers from another.
    FakeNode first = fakeNode();
    FakeNode second = fakeNode();
    NodeId other = NodeId.of(1);
    NettyTransport transport =
        new NettyTransport(
            Map.of(NODE, first.address(), other, second.address()),
            NettyTransport.MAX_PENDING_BYTES,
            NettyTransport.DATAGRAM_BUFFER_BYTES,
            System::nanoTime);
    closing.add(transport);
    Recorder heard = listen(transport);

    try (DatagramSocket elsewhere = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
      elsewhere.send(new DatagramPacket(new byte[] {1}, 1, transport.requestSocket(other)));
      elsewhere.send(new DatagramPacket(new byte[] {2}, 1, transport.requestSocket(NODE)));
    }

    awaitHeard(transport, () -> heard.frames.size() == 2);
    for (int i = 0; i < 2; i++) {
      assertEquals(heard.frames.get(i)[0] == 1 ? other : NODE, heard.nodes.get(i));
    }
  }

  @Test
  void anAttemptNoNodeAcceptsEndsInOneDisconnect() throws Exception {
    FakeNode node = fakeNode();
    NodeAddress address = node.address();
    int closedPort = node.world.getLocalPort();
    node.world.close();
    NettyTransport transport =
        transport(
            new NodeAddress(
                address.host(), closedPort, address.controlPort(), address.requestPort()),
            NettyTransport.MAX_PENDING_BYTES);
    Recorder heard = listen(transport);

    transport.connect(NODE);

    awaitHeard(transport, () -> heard.disconnected == 1);
    assertEquals(0, heard.connected);
  }

  @Test
  void aClosedConnectionLosesTheLink() throws Exception {
    FakeNode node = fakeNode();
    NettyTransport transport = transport(node.address(), NettyTransport.MAX_PENDING_BYTES);
    Recorder heard = listen(transport);
    Link link = up(transport, node, heard);

    link.control.close();

    awaitHeard(transport, () -> heard.disconnected == 1);
    // Whatever is sent now is discarded.
    transport.send(NODE, Channel.WORLD, ByteBuffer.wrap(new byte[] {1}));
    transport.send(NODE, Channel.REQUEST, ByteBuffer.wrap(new byte[] {1}));
    assertEquals(1, heard.connected);
  }

  @Test
  void aNewAttemptReplacesTheLinkWithoutReportingTheOldOneLost() throws Exception {
    FakeNode node = fakeNode();
    NettyTransport transport = transport(node.address(), NettyTransport.MAX_PENDING_BYTES);
    Recorder heard = listen(transport);
    Link first = up(transport, node, heard);

    transport.connect(NODE);
    Link second = new Link(node.world.accept(), node.control.accept());
    closing.add(second.world);
    closing.add(second.control);
    awaitHeard(transport, () -> heard.connected == 2);

    // A frame on the old connection is lost with it, and frames now go on the new one.
    writeFrameQuietly(first.control.getOutputStream(), new byte[] {1});
    transport.send(NODE, Channel.WORLD, ByteBuffer.wrap(new byte[] {2}));
    assertArrayEquals(new byte[] {2}, readFrame(second.world.getInputStream()));
    writeFrame(second.control.getOutputStream(), new byte[] {3});
    awaitHeard(transport, () -> !heard.frames.isEmpty());
    assertArrayEquals(new byte[] {3}, heard.frames.get(heard.frames.size() - 1));
    assertEquals(0, heard.disconnected);
  }

  @Test
  void aNodeThatStopsReadingLosesItsLinkOnceItHoldsTooMuch() throws Exception {
    FakeNode node = fakeNode();
    int cap = 256 << 10;
    NettyTransport transport = transport(node.address(), cap);
    Recorder heard = listen(transport);
    up(transport, node, heard);

    // The node never reads, so once the kernel's buffers fill, the frames pile up unsent.
    byte[] frame = new byte[64 << 10];
    for (int sent = 0; sent < 2_000 && heard.disconnected == 0; sent++) {
      transport.send(NODE, Channel.WORLD, ByteBuffer.wrap(frame));
      if (sent % 16 == 0) {
        transport.drain();
      }
    }

    awaitHeard(transport, () -> heard.disconnected == 1);
  }

  @Test
  void aListenerThatThrowsDoesNotKeepTheRestFromBeingHandedOver() throws Exception {
    FakeNode node = fakeNode();
    NettyTransport transport = transport(node.address(), NettyTransport.MAX_PENDING_BYTES);
    FailsOnce listener = new FailsOnce();
    transport.listen(listener);
    Link link = up(transport, node, listener.heard);

    writeFrame(link.control.getOutputStream(), new byte[] {1});
    writeFrame(link.control.getOutputStream(), new byte[] {2});

    awaitHeard(transport, () -> listener.heard.frames.size() == 1);
    assertTrue(listener.failed);
    assertArrayEquals(new byte[] {2}, listener.heard.frames.get(0));
    assertEquals(0, listener.heard.disconnected);
  }

  @Test
  void theTransportRefusesToBeMisused() {
    FakeNode node = fakeNode();
    NettyTransport transport = transport(node.address(), NettyTransport.MAX_PENDING_BYTES);

    // Nothing to send on yet, so nothing happens.
    transport.send(NODE, Channel.WORLD, ByteBuffer.wrap(new byte[] {1}));
    assertThrows(IllegalStateException.class, () -> transport.connect(NODE));
    transport.listen(new Recorder());
    assertThrows(IllegalStateException.class, () -> transport.listen(new Recorder()));
    assertThrows(IllegalArgumentException.class, () -> transport.connect(NodeId.of(5)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NettyTransport(
                Map.of(NODE, new NodeAddress("no-such-host.invalid", 1, 2, 3)),
                NettyTransport.MAX_PENDING_BYTES,
                NettyTransport.DATAGRAM_BUFFER_BYTES,
                System::nanoTime));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new NettyTransport(
                Map.of(NODE, node.address(), NodeId.of(1), node.address()),
                NettyTransport.MAX_PENDING_BYTES,
                NettyTransport.DATAGRAM_BUFFER_BYTES,
                System::nanoTime));
  }

  @Test
  void eachRequestSocketTakesTheBuffersAsked() {
    // Far below any system default, which the socket would otherwise keep.
    NettyTransport transport =
        new NettyTransport(
            Map.of(NODE, fakeNode().address()),
            NettyTransport.MAX_PENDING_BYTES,
            4096,
            System::nanoTime);
    closing.add(transport);

    DatagramChannelConfig config = transport.requestConfig(NODE);
    for (int granted : new int[] {config.getReceiveBufferSize(), config.getSendBufferSize()}) {
      assertTrue(granted >= 4096 && granted < 65536, "granted " + granted);
    }
  }

  /** The node's side of a link. */
  private record Link(Socket world, Socket control) {}

  /** Sockets standing in for a compute node, on loopback. */
  private static final class FakeNode implements AutoCloseable {
    final ServerSocket world;
    final ServerSocket control;
    final DatagramSocket requests;

    FakeNode() throws IOException {
      InetAddress loopback = InetAddress.getLoopbackAddress();
      world = new ServerSocket();
      // A small window, so a node that stops reading holds little in the kernel.
      world.setReceiveBufferSize(4096);
      world.bind(new InetSocketAddress(loopback, 0));
      control = new ServerSocket(0, 4, loopback);
      requests = new DatagramSocket(0, loopback);
      world.setSoTimeout((int) WAIT_MILLIS);
      control.setSoTimeout((int) WAIT_MILLIS);
      requests.setSoTimeout((int) WAIT_MILLIS);
    }

    NodeAddress address() {
      return new NodeAddress(
          InetAddress.getLoopbackAddress().getHostAddress(),
          world.getLocalPort(),
          control.getLocalPort(),
          requests.getLocalPort());
    }

    @Override
    public void close() throws IOException {
      world.close();
      control.close();
      requests.close();
    }
  }

  /** Everything the transport reported, in order. */
  private static final class Recorder implements Transport.Listener {
    final List<NodeId> nodes = new ArrayList<>();
    final List<Channel> channels = new ArrayList<>();
    final List<byte[]> frames = new ArrayList<>();
    final List<Long> arrived = new ArrayList<>();
    int connected;
    int disconnected;

    @Override
    public void onFrame(NodeId node, Channel channel, ByteBuffer frame, long arrivedNanos) {
      byte[] bytes = new byte[frame.remaining()];
      frame.get(bytes);
      nodes.add(node);
      channels.add(channel);
      frames.add(bytes);
      arrived.add(arrivedNanos);
    }

    @Override
    public void onConnected(NodeId node) {
      connected++;
    }

    @Override
    public void onDisconnected(NodeId node) {
      disconnected++;
    }
  }

  /** Throws on the first frame it is handed, and records everything else. */
  private static final class FailsOnce implements Transport.Listener {
    final Recorder heard = new Recorder();
    boolean failed;

    @Override
    public void onFrame(NodeId node, Channel channel, ByteBuffer frame, long arrivedNanos) {
      if (!failed) {
        failed = true;
        throw new IllegalStateException("the listener failed");
      }
      heard.onFrame(node, channel, frame, arrivedNanos);
    }

    @Override
    public void onConnected(NodeId node) {
      heard.onConnected(node);
    }

    @Override
    public void onDisconnected(NodeId node) {
      heard.onDisconnected(node);
    }
  }

  private FakeNode fakeNode() {
    try {
      FakeNode node = new FakeNode();
      closing.add(node);
      return node;
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private NettyTransport transport(NodeAddress address, int cap) {
    return transport(address, cap, System::nanoTime);
  }

  private NettyTransport transport(NodeAddress address, int cap, LongSupplier nanoTime) {
    NettyTransport transport =
        new NettyTransport(
            Map.of(NODE, address), cap, NettyTransport.DATAGRAM_BUFFER_BYTES, nanoTime);
    closing.add(transport);
    return transport;
  }

  private static Recorder listen(NettyTransport transport) {
    Recorder heard = new Recorder();
    transport.listen(heard);
    return heard;
  }

  /** Connects and waits for the link to be reported up. */
  private Link up(NettyTransport transport, FakeNode node, Recorder heard)
      throws IOException, InterruptedException {
    transport.connect(NODE);
    Link link = new Link(node.world.accept(), node.control.accept());
    closing.add(link.world);
    closing.add(link.control);
    awaitHeard(transport, () -> heard.connected == 1);
    return link;
  }

  /** Hands over what the transport heard until {@code condition} holds. */
  private static void awaitHeard(NettyTransport transport, BooleanSupplier condition)
      throws InterruptedException {
    long deadline = System.nanoTime() + WAIT_MILLIS * 1_000_000;
    while (System.nanoTime() < deadline) {
      transport.drain();
      if (condition.getAsBoolean()) {
        return;
      }
      Thread.sleep(5);
    }
    transport.drain();
    assertTrue(condition.getAsBoolean(), "the transport did not report it in time");
  }

  private static byte[] readFrame(InputStream in) throws IOException {
    DataInputStream data = new DataInputStream(in);
    byte[] header = new byte[Integer.BYTES];
    data.readFully(header);
    byte[] payload = new byte[ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt()];
    data.readFully(payload);
    return payload;
  }

  private static void writeFrame(OutputStream out, byte[] payload) throws IOException {
    ByteBuffer framed = ByteBuffer.allocate(Integer.BYTES + payload.length);
    framed.order(ByteOrder.LITTLE_ENDIAN).putInt(payload.length).put(payload);
    out.write(framed.array());
    out.flush();
  }

  /** As above, on a connection the other side may have closed. */
  private static void writeFrameQuietly(OutputStream out, byte[] payload) {
    try {
      writeFrame(out, payload);
    } catch (IOException closed) {
      // Lost with the connection, as intended.
    }
  }
}
