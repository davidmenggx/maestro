package com.smpler.maestro.net;

import com.google.flatbuffers.FlatBufferBuilder;
import java.nio.ByteBuffer;
import maestro.fbs.Ping;

/**
 * Compile-time proof that the FlatBuffers Java runtime and the flatc-generated sources are on the
 * classpath together. Builds and reads back a {@link Ping} control message.
 *
 * <p>THIS SHOULD BE REPLACED BY REAL FRAMING/TRANSPORT CODE AFTER IT IS IMPLEMENTED.
 */
public final class WireProbe {

  private WireProbe() {}

  /** Serialise a liveness Ping into a FlatBuffers buffer. */
  public static ByteBuffer buildPing(long sequence, long sendMicros) {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    Ping.startPing(builder);
    Ping.addSequence(builder, sequence);
    Ping.addSendMicros(builder, sendMicros);
    int ping = Ping.endPing(builder);
    builder.finish(ping);
    return builder.dataBuffer();
  }

  /** Read a Ping's sequence number back out of a buffer. */
  public static long readSequence(ByteBuffer buffer) {
    return Ping.getRootAsPing(buffer).sequence();
  }
}
