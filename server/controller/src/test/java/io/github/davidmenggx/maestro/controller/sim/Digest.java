package io.github.davidmenggx.maestro.controller.sim;

/** Running hash of everything a run does. Two runs of one seed must end on the same value. */
public final class Digest {

  private static final long OFFSET = 0xcbf29ce484222325L;
  private static final long PRIME = 0x100000001b3L;

  private long value = OFFSET;

  public Digest mix(long v) {
    for (int shift = 0; shift < 64; shift += 8) {
      value = (value ^ ((v >>> shift) & 0xFF)) * PRIME;
    }
    return this;
  }

  public Digest mix(String s) {
    for (int i = 0; i < s.length(); i++) {
      value = (value ^ s.charAt(i)) * PRIME;
    }
    return this;
  }

  public Digest mix(byte[] bytes) {
    for (byte b : bytes) {
      value = (value ^ (b & 0xFF)) * PRIME;
    }
    return this;
  }

  public long value() {
    return value;
  }
}
