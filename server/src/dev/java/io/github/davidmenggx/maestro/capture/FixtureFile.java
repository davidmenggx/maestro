package io.github.davidmenggx.maestro.capture;

import com.google.flatbuffers.FlatBufferBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import maestro.fbs.ParityCase;
import maestro.fbs.ParityFixture;

/** Writes a {@code ParityFixture}. */
final class FixtureFile {

  /**
   * One recorded search, as the three frames that replay it, made after the first {@code
   * worldFrames} frames of the world stream.
   */
  record Case(
      String entity,
      boolean exact,
      byte[] mobClass,
      byte[] request,
      byte[] response,
      int worldFrames) {}

  private FixtureFile() {}

  /** {@code world} is the framed world stream, see {@link #frame}. */
  static void write(Path file, long seed, byte[] world, List<Case> cases) throws IOException {
    FlatBufferBuilder builder = new FlatBufferBuilder(world.length + 1024 * (cases.size() + 1));
    int[] offsets = new int[cases.size()];
    for (int i = 0; i < cases.size(); i++) {
      Case recorded = cases.get(i);
      offsets[i] =
          ParityCase.createParityCase(
              builder,
              builder.createString(recorded.entity()),
              recorded.exact(),
              ParityCase.createMobClassVector(builder, recorded.mobClass()),
              ParityCase.createRequestVector(builder, recorded.request()),
              ParityCase.createResponseVector(builder, recorded.response()),
              recorded.worldFrames());
    }
    int casesVector = ParityFixture.createCasesVector(builder, offsets);
    int worldVector = ParityFixture.createWorldVector(builder, world);
    ParityFixture.finishParityFixtureBuffer(
        builder, ParityFixture.createParityFixture(builder, seed, worldVector, casesVector));
    Files.createDirectories(file.getParent());
    Files.write(file, builder.sizedByteArray());
  }

  /** Each payload as the wire frames it: a little-endian 32-bit length, then the payload. */
  static byte[] frame(List<byte[]> payloads) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] payload : payloads) {
      int length = payload.length;
      out.write(length & 0xFF);
      out.write((length >>> 8) & 0xFF);
      out.write((length >>> 16) & 0xFF);
      out.write((length >>> 24) & 0xFF);
      out.writeBytes(payload);
    }
    return out.toByteArray();
  }
}
