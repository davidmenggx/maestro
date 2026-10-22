package io.github.davidmenggx.maestro.controller.sim;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.davidmenggx.maestro.controller.env.NodeId;
import io.github.davidmenggx.maestro.controller.env.SeededRng;
import io.github.davidmenggx.maestro.controller.env.Transport.Channel;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import maestro.fbs.ConformanceCase;
import maestro.fbs.ConformanceFixture;
import maestro.fbs.ControlReplyFrame;
import maestro.fbs.ControlReplyMsg;
import maestro.fbs.Effect;
import maestro.fbs.EffectKind;
import maestro.fbs.Heartbeat;
import maestro.fbs.Hello;
import maestro.fbs.LinkPort;
import maestro.fbs.PathResponse;
import maestro.fbs.ResponseFrame;
import maestro.fbs.ResponseMsg;
import maestro.fbs.Step;
import maestro.fbs.StepKind;
import maestro.fbs.UnknownProfile;
import org.junit.jupiter.api.Test;

/**
 * {@link ModeledNode} passes every conformance case the C++ node passes, and the committed fixture
 * is what {@link ConformanceScripts} writes. {@code make conformance} writes it again.
 */
class ConformanceTest {

  /** Steps per millisecond: a step is 50 microseconds. */
  private static final long STEPS_PER_MILLI = 20;

  @Test
  void theCommittedFixtureIsWhatTheScriptsWrite() throws IOException {
    Path file = Path.of(System.getProperty("maestro.conformance"), "node.bin");
    byte[] written = ConformanceScripts.fixture();
    if (Boolean.getBoolean("maestro.conformance.write")) {
      Files.createDirectories(file.getParent());
      Files.write(file, written);
    }
    assertTrue(Files.exists(file), file + " is missing; run `make conformance`");
    assertArrayEquals(
        Files.readAllBytes(file), written, file + " is stale; run `make conformance`");
  }

  @Test
  void theModeledNodePassesEveryCase() {
    ConformanceFixture fixture =
        ConformanceFixture.getRootAsConformanceFixture(
            ByteBuffer.wrap(ConformanceScripts.fixture()));
    assertTrue(fixture.casesLength() > 0);
    for (int c = 0; c < fixture.casesLength(); c++) {
      replay(fixture.cases(c));
    }
  }

  private static void replay(ConformanceCase script) {
    Driver driver = new Driver();
    ModeledNode node =
        new ModeledNode(NodeId.of(0), driver, ModeledNode.Service.MANUAL, SeededRng.of(1));
    for (int s = 0; s < script.stepsLength(); s++) {
      Step step = script.steps(s);
      driver.effects.clear();
      Channel port = step.port() == LinkPort.World ? Channel.WORLD : Channel.CONTROL;
      switch (step.kind()) {
        case StepKind.Connect -> node.connect(port, step.frameAsByteBuffer());
        case StepKind.Stream -> node.receive(port, step.frameAsByteBuffer());
        case StepKind.Disconnect -> node.disconnected(port);
        case StepKind.Datagram -> node.receive(Channel.REQUEST, step.frameAsByteBuffer());
        case StepKind.Advance -> driver.now += step.millis() * STEPS_PER_MILLI;
        case StepKind.Serve -> node.serve((int) step.count());
        case StepKind.Heartbeat -> node.heartbeatDue();
        default -> throw new AssertionError("unknown step " + step.kind());
      }
      List<String> expected = new ArrayList<>();
      for (int e = 0; e < step.effectsLength(); e++) {
        expected.add(describe(step.effects(e)));
      }
      assertEquals(
          expected,
          driver.effects,
          script.name() + ", step " + s + " (" + StepKind.name(step.kind()) + ")");
    }
  }

  /** The fields an effect of its kind compares, as the C++ replay compares them. */
  private static String describe(Effect effect) {
    return switch (effect.kind()) {
      case EffectKind.Hello -> hello(effect.run(), effect.regionId(), effect.sequence());
      case EffectKind.Heartbeat -> "heartbeat depth=" + effect.depth();
      case EffectKind.Answer -> answer(effect.requestId(), effect.run(), effect.sequence());
      case EffectKind.UnknownProfile -> unknownProfile(effect.requestId(), effect.run());
      case EffectKind.LinkDropped -> "link dropped";
      case EffectKind.Refused -> "refused";
      default -> throw new AssertionError("unknown effect " + effect.kind());
    };
  }

  private static String hello(long run, long region, long sequence) {
    return "hello run=" + run + " region=" + region + " sequence=" + sequence;
  }

  private static String answer(long requestId, long run, long sequence) {
    return "answer id=" + requestId + " run=" + run + " sequence=" + sequence;
  }

  private static String unknownProfile(long requestId, long run) {
    return "unknown profile id=" + requestId + " run=" + run;
  }

  /** The node's surroundings during a replay: a clock moved only by the script, and a recorder. */
  private static final class Driver implements ModeledNode.Host {

    private final List<String> effects = new ArrayList<>();
    private long now;

    @Override
    public long now() {
      return now;
    }

    @Override
    public void send(Channel channel, byte[] frame) {
      ByteBuffer bytes = ByteBuffer.wrap(frame);
      if (channel == Channel.CONTROL) {
        ControlReplyFrame reply = ControlReplyFrame.getRootAsControlReplyFrame(bytes);
        if (reply.bodyType() == ControlReplyMsg.Hello) {
          Hello hello = (Hello) reply.body(new Hello());
          effects.add(hello(hello.run(), hello.regionId(), hello.lastSequence()));
        } else {
          Heartbeat heartbeat = (Heartbeat) reply.body(new Heartbeat());
          effects.add("heartbeat depth=" + heartbeat.queueDepth());
        }
        return;
      }
      ResponseFrame response = ResponseFrame.getRootAsResponseFrame(bytes);
      if (response.bodyType() == ResponseMsg.PathResponse) {
        PathResponse answer = (PathResponse) response.body(new PathResponse());
        effects.add(answer(answer.requestId(), response.run(), answer.worldSequence()));
      } else {
        UnknownProfile refusal = (UnknownProfile) response.body(new UnknownProfile());
        effects.add(unknownProfile(refusal.requestId(), response.run()));
      }
    }

    @Override
    public void linkDropped() {
      effects.add("link dropped");
    }

    @Override
    public void refused(Channel port) {
      effects.add("refused");
    }

    @Override
    public void schedule(long delaySteps, String name, Runnable action) {}

    @Override
    public void applied(ModeledNode node, long run, long region, long sequence) {}

    @Override
    public void assigned(ModeledNode node, long run, long region, long sequence) {}
  }
}
