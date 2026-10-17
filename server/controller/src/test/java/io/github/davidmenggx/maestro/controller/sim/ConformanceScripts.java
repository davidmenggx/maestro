package io.github.davidmenggx.maestro.controller.sim;

import com.google.flatbuffers.FlatBufferBuilder;
import io.github.davidmenggx.maestro.controller.wire.Frames;
import io.github.davidmenggx.maestro.controller.world.MobClass;
import io.github.davidmenggx.maestro.controller.world.PathTypes;
import io.github.davidmenggx.maestro.controller.world.Pos;
import io.github.davidmenggx.maestro.controller.world.WorldEvent;
import io.github.davidmenggx.maestro.controller.world.WorldSource;
import java.util.ArrayList;
import java.util.List;
import maestro.fbs.ConformanceCase;
import maestro.fbs.ConformanceFixture;
import maestro.fbs.ControlFrame;
import maestro.fbs.ControlMsg;
import maestro.fbs.Effect;
import maestro.fbs.EffectKind;
import maestro.fbs.Join;
import maestro.fbs.LinkPort;
import maestro.fbs.PathType;
import maestro.fbs.Step;
import maestro.fbs.StepKind;

/**
 * The conformance cases both the C++ node and {@link ModeledNode} must pass: what a node does with
 * links, profiles, requests, cancels and world frames, step by step. They are the contract in
 * {@code schema/conformance.fbs}, written once here and saved as {@code
 * fixtures/conformance/node.bin} for the node's tests.
 */
final class ConformanceScripts {

  /** The controller run most cases use, and another. */
  private static final long RUN = 7;

  private static final long OTHER_RUN = 8;

  private static final int REGION = 3;
  private static final int SIZE = 16;
  private static final short MOB_CLASS = 1;

  private static final WorldEvent.Search SEARCH = new WorldEvent.Search(0, 16f, 256, 3);

  /** A flat chunk: stone below y 8 and air above, in a column 32 blocks tall from y 0. */
  private static final WorldSource FLAT =
      new WorldSource() {
        @Override
        public MobClass mobClass(short classId) {
          return null;
        }

        @Override
        public int minY() {
          return 0;
        }

        @Override
        public int height() {
          return 32;
        }

        @Override
        public byte pathTypeAt(int x, int y, int z) {
          return (byte) (y < 8 ? PathType.BLOCKED : PathType.OPEN);
        }

        @Override
        public int floorAt(int x, int y, int z) {
          return y < 8 ? 16 : 0;
        }
      };

  private ConformanceScripts() {}

  /** Every case, encoded as a {@code ConformanceFixture}. */
  static byte[] fixture() {
    List<Script> cases = new ArrayList<>();

    cases.add(
        new Script("a fresh node reports nothing held")
            .connect(LinkPort.Control, Frames.joinControl(RUN, 1), hello(0, 0, 0))
            .connect(LinkPort.World, Frames.joinWorld(RUN, 1))
            .heartbeat(heartbeat(0)));

    cases.add(
        new Script("a hello follows the control join and reports what is held")
                .connect(LinkPort.World, Frames.joinWorld(RUN, 1))
                .connect(LinkPort.Control, Frames.joinControl(RUN, 1), hello(0, 0, 0))
                .stream(LinkPort.World, assignment(RUN, 0, false))
                .stream(LinkPort.World, chunk(1))
                .disconnect(LinkPort.Control, linkDropped())
                .connect(LinkPort.Control, Frames.joinControl(RUN, 2), hello(RUN, REGION, 1))
                .connect(LinkPort.World, Frames.joinWorld(RUN, 2))
                .stream(LinkPort.World, assignment(RUN, 1, true))
                .stream(LinkPort.World, delta(2))
                .disconnect(LinkPort.World, linkDropped())
                .connect(LinkPort.Control, Frames.joinControl(RUN, 3), hello(RUN, REGION, 2)));

    cases.add(
        new Script("an assignment alone at a later sequence is held")
            .link(RUN, 1).stream(LinkPort.World, assignment(RUN, 5, false))
                .disconnect(LinkPort.World, linkDropped())
                .connect(LinkPort.Control, Frames.joinControl(RUN, 2), hello(RUN, REGION, 5)));

    cases.add(
        held("a request is answered once and a copy gets the same answer")
            .datagram(requests(RUN, REGION, 10))
            .datagram(requests(RUN, REGION, 10))
            .heartbeat(heartbeat(1))
            .serve(0, answer(10, RUN, 1))
            .datagram(requests(RUN, REGION, 10), answer(10, RUN, 1))
            .heartbeat(heartbeat(0)));

    cases.add(
        held("an answer is kept for 500 ms")
            .datagram(requests(RUN, REGION, 10))
            .serve(0, answer(10, RUN, 1))
            .advance(499)
            .datagram(requests(RUN, REGION, 10), answer(10, RUN, 1))
            .advance(1)
            .datagram(requests(RUN, REGION, 10))
            .serve(0, answer(10, RUN, 1)));

    cases.add(
        held("a cancel drops a waiting request and its later copies")
            .datagram(requests(RUN, REGION, 10, 11))
            .datagram(Frames.cancels(RUN, List.of(10L)))
            .heartbeat(heartbeat(1))
            .serve(0, answer(11, RUN, 1))
            .datagram(requests(RUN, REGION, 10))
            .serve(0));

    cases.add(
        held("a cancel that arrives first drops the request until it expires")
            .datagram(Frames.cancels(RUN, List.of(12L)))
            .datagram(requests(RUN, REGION, 12))
            .serve(0)
            .advance(500)
            .datagram(requests(RUN, REGION, 12))
            .serve(0, answer(12, RUN, 1)));

    cases.add(
        held("a cancel leaves an answered request alone")
            .datagram(requests(RUN, REGION, 10))
            .serve(0, answer(10, RUN, 1))
            .datagram(Frames.cancels(RUN, List.of(10L)))
            .datagram(requests(RUN, REGION, 10), answer(10, RUN, 1)));

    cases.add(
        held("a canceled request is skipped and not counted by serve")
            .datagram(requests(RUN, REGION, 10, 11, 12))
            .datagram(Frames.cancels(RUN, List.of(10L)))
            .serve(1, answer(11, RUN, 1))
            .heartbeat(heartbeat(1))
            .serve(1, answer(12, RUN, 1)));

    cases.add(
        held("a request queued again after its cancel expired is served once")
            .datagram(requests(RUN, REGION, 10))
            .datagram(Frames.cancels(RUN, List.of(10L)))
            .advance(500)
            .datagram(requests(RUN, REGION, 10))
            .heartbeat(heartbeat(1))
            .serve(0, answer(10, RUN, 1))
            .serve(0));

    cases.add(
        world("a request naming an unknown profile is refused at once and not remembered")
            .datagram(requests(RUN, REGION, 10), unknownProfile(10, RUN))
            .stream(LinkPort.Control, Frames.mobClass(profile(MOB_CLASS, 0.6f)))
            .datagram(requests(RUN, REGION, 10))
            .serve(0, answer(10, RUN, 1)));

    cases.add(
        held("a request frame of another run is dropped")
            .datagram(requests(OTHER_RUN, REGION, 10))
            .datagram(requests(RUN, REGION, 11))
            .datagram(Frames.cancels(OTHER_RUN, List.of(11L)))
            .serve(0, answer(11, RUN, 1)));

    cases.add(
        new Script("a datagram with no link is dropped")
            .datagram(requests(RUN, REGION, 10))
            .datagram(Frames.cancels(RUN, List.of(10L))));

    cases.add(
        held("a request for another region is answered as if no area were held")
            .datagram(requests(RUN, REGION + 1, 10))
            .serve(0, answer(10, RUN, 0)));

    cases.add(
        new Script("a node holding no area answers at sequence 0")
            .link(RUN, 1).stream(LinkPort.Control, Frames.mobClass(profile(MOB_CLASS, 0.6f)))
                .datagram(requests(RUN, REGION, 10))
                .serve(0, answer(10, RUN, 0)));

    cases.add(
        held("a profile that breaks the contract drops the link and keeps the world").stream(
                LinkPort.Control, brokenProfile(), linkDropped())
            .connect(LinkPort.Control, Frames.joinControl(RUN, 2), hello(RUN, REGION, 1)));

    cases.add(
        new Script("a refused world frame drops the world and the link")
            .link(RUN, 1).stream(LinkPort.World, assignment(RUN, 0, false)).stream(
                    LinkPort.World, chunk(2), linkDropped())
                .connect(LinkPort.Control, Frames.joinControl(RUN, 2), hello(0, 0, 0)));

    cases.add(
        world("a resume at another sequence is refused")
            .disconnect(LinkPort.World, linkDropped())
            .connect(LinkPort.Control, Frames.joinControl(RUN, 2), hello(RUN, REGION, 1))
            .connect(LinkPort.World, Frames.joinWorld(RUN, 2))
            .stream(LinkPort.World, assignment(RUN, 0, true), linkDropped())
            .connect(LinkPort.Control, Frames.joinControl(RUN, 3), hello(0, 0, 0)));

    cases.add(
        held("losing the link forgets profiles and requests")
            .datagram(requests(RUN, REGION, 10))
            .disconnect(LinkPort.World, linkDropped())
            .connect(LinkPort.Control, Frames.joinControl(RUN, 2), hello(RUN, REGION, 1))
            .connect(LinkPort.World, Frames.joinWorld(RUN, 2))
            .serve(0)
            .datagram(requests(RUN, REGION, 10), unknownProfile(10, RUN)));

    cases.add(
        held("a join of another run replaces the link")
            .connect(
                LinkPort.Control,
                Frames.joinControl(OTHER_RUN, 1),
                linkDropped(),
                hello(RUN, REGION, 1))
            .connect(LinkPort.World, Frames.joinWorld(OTHER_RUN, 1))
            .datagram(requests(RUN, REGION, 10))
            .datagram(requests(OTHER_RUN, REGION, 11), unknownProfile(11, OTHER_RUN))
            .stream(LinkPort.World, assignment(OTHER_RUN, 0, false))
            .stream(LinkPort.Control, Frames.mobClass(profile(MOB_CLASS, 0.6f)))
            .datagram(requests(OTHER_RUN, REGION, 11))
            .serve(0, answer(11, OTHER_RUN, 0)));

    cases.add(
        new Script("a later attempt replaces the link and an earlier one is refused")
            .link(RUN, 2)
            .connect(LinkPort.World, Frames.joinWorld(RUN, 1), refused())
            .connect(LinkPort.Control, Frames.joinControl(RUN, 3), linkDropped(), hello(0, 0, 0))
            .connect(LinkPort.World, Frames.joinWorld(RUN, 2), refused())
            .connect(LinkPort.World, Frames.joinWorld(RUN, 3))
            .heartbeat(heartbeat(0)));

    cases.add(
        new Script("a connection whose first frame is not a join is refused")
            .link(RUN, 1)
            .connect(LinkPort.World, assignment(RUN, 0, false), refused())
            .connect(LinkPort.Control, Frames.mobClass(profile(MOB_CLASS, 0.6f)), refused())
            .connect(LinkPort.Control, rawJoinControl(0, 4), refused())
            .connect(LinkPort.World, rawJoinWorld(RUN, 0), refused())
            .heartbeat(heartbeat(0)));

    cases.add(
        new Script("a second connection for a joined port is refused")
            .link(RUN, 1)
            .connect(LinkPort.Control, Frames.joinControl(RUN, 1), refused())
            .connect(LinkPort.World, Frames.joinWorld(RUN, 1), refused())
            .heartbeat(heartbeat(0)));

    cases.add(
        new Script("the token of a dropped link is refused")
            .link(RUN, 1)
            .disconnect(LinkPort.World, linkDropped())
            .connect(LinkPort.Control, Frames.joinControl(RUN, 1), refused())
            .connect(LinkPort.Control, Frames.joinControl(RUN, 2), hello(0, 0, 0)));

    cases.add(
        held("a join after the first frame is ignored").stream(
                LinkPort.World, Frames.joinWorld(RUN, 1))
            .stream(LinkPort.Control, Frames.joinControl(OTHER_RUN, 9))
            .heartbeat(heartbeat(0))
            .disconnect(LinkPort.World, linkDropped())
            .connect(LinkPort.Control, Frames.joinControl(RUN, 2), hello(RUN, REGION, 1)));

    cases.add(
        new Script("the heartbeat needs the control connection and counts waiting requests")
            .heartbeat()
            .connect(LinkPort.World, Frames.joinWorld(RUN, 1))
            .heartbeat()
            .connect(LinkPort.Control, Frames.joinControl(RUN, 1), hello(0, 0, 0))
            .heartbeat(heartbeat(0)));

    FlatBufferBuilder builder = new FlatBufferBuilder(1 << 16);
    int[] offsets = new int[cases.size()];
    for (int i = 0; i < cases.size(); i++) {
      offsets[i] = cases.get(i).encode(builder);
    }
    int vector = ConformanceFixture.createCasesVector(builder, offsets);
    ConformanceFixture.finishConformanceFixtureBuffer(
        builder, ConformanceFixture.createConformanceFixture(builder, vector));
    return builder.sizedByteArray();
  }

  /** A link of {@link #RUN} holding the area at sequence 1, with no profile. */
  private static Script world(String name) {
    return new Script(name)
        .link(RUN, 1).stream(LinkPort.World, assignment(RUN, 0, false)).stream(
            LinkPort.World, chunk(1));
  }

  /** As {@link #world}, with the mob's profile registered. */
  private static Script held(String name) {
    return world(name).stream(LinkPort.Control, Frames.mobClass(profile(MOB_CLASS, 0.6f)));
  }

  private static byte[] assignment(long run, long sequence, boolean resume) {
    return Frames.assignment(run, REGION, SIZE, sequence, resume, FLAT);
  }

  private static byte[] chunk(long sequence) {
    return Frames.chunkSnapshot(sequence, 0, 0, Frames.readChunk(FLAT, 0, 0));
  }

  private static byte[] delta(long sequence) {
    return Frames.delta(
        sequence,
        List.of(new WorldEvent.BlockChanged(0, new Pos(4, 8, 4), (byte) PathType.BLOCKED, 16)),
        0,
        0);
  }

  private static byte[] requests(long run, int region, long... requestIds) {
    List<Frames.Request> batch = new ArrayList<>();
    for (long requestId : requestIds) {
      batch.add(
          new Frames.Request(requestId, MOB_CLASS, new Pos(2, 8, 2), new Pos(10, 8, 10), SEARCH));
    }
    return Frames.requests(run, region, batch, 0, 0);
  }

  private static MobClass profile(short classId, float width) {
    return new MobClass(
        classId,
        new MobClass.Shape(width, 1.8f, 0.6f),
        new MobClass.Abilities(true, false, false, false),
        new float[PathTypes.count()]);
  }

  /** A profile no shape allows, which the encoder refuses to build. */
  private static byte[] brokenProfile() {
    FlatBufferBuilder builder = new FlatBufferBuilder(128);
    int malus = maestro.fbs.MobClass.createMalusVector(builder, new float[PathTypes.count()]);
    int body =
        maestro.fbs.MobClass.createMobClass(
            builder, MOB_CLASS, 0f, 1.8f, malus, 0.6f, true, false, false, false);
    builder.finish(ControlFrame.createControlFrame(builder, ControlMsg.MobClass, body));
    return builder.sizedByteArray();
  }

  /** A `Join` with any fields, which the encoder refuses to build with a zero one. */
  private static byte[] rawJoinControl(long run, long link) {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    int join = Join.createJoin(builder, run, link);
    builder.finish(ControlFrame.createControlFrame(builder, ControlMsg.Join, join));
    return builder.sizedByteArray();
  }

  private static byte[] rawJoinWorld(long run, long link) {
    FlatBufferBuilder builder = new FlatBufferBuilder(64);
    int join = Join.createJoin(builder, run, link);
    builder.finish(
        maestro.fbs.WorldFrame.createWorldFrame(builder, maestro.fbs.WorldMsg.Join, join));
    return builder.sizedByteArray();
  }

  /** One expected effect, with only the fields its kind compares set. */
  record Expected(int kind, long run, long regionId, long sequence, long depth, long requestId) {

    int encode(FlatBufferBuilder builder) {
      return Effect.createEffect(builder, kind, run, regionId, sequence, depth, requestId);
    }
  }

  static Expected hello(long run, long region, long sequence) {
    return new Expected(EffectKind.Hello, run, region, sequence, 0, 0);
  }

  static Expected heartbeat(long depth) {
    return new Expected(EffectKind.Heartbeat, 0, 0, 0, depth, 0);
  }

  static Expected answer(long requestId, long run, long sequence) {
    return new Expected(EffectKind.Answer, run, 0, sequence, 0, requestId);
  }

  static Expected unknownProfile(long requestId, long run) {
    return new Expected(EffectKind.UnknownProfile, run, 0, 0, 0, requestId);
  }

  static Expected linkDropped() {
    return new Expected(EffectKind.LinkDropped, 0, 0, 0, 0, 0);
  }

  static Expected refused() {
    return new Expected(EffectKind.Refused, 0, 0, 0, 0, 0);
  }

  /** One case being written. */
  private static final class Script {

    private record Spec(
        int kind, int port, byte[] frame, int millis, int count, Expected[] effects) {}

    private final String name;
    private final List<Spec> steps = new ArrayList<>();

    Script(String name) {
      this.name = name;
    }

    /** Opens both connections of attempt {@code link}. */
    Script link(long run, long link) {
      return connect(LinkPort.World, Frames.joinWorld(run, link))
          .connect(LinkPort.Control, Frames.joinControl(run, link), hello(0, 0, 0));
    }

    Script connect(int port, byte[] frame, Expected... effects) {
      return add(StepKind.Connect, port, frame, 0, 0, effects);
    }

    Script stream(int port, byte[] frame, Expected... effects) {
      return add(StepKind.Stream, port, frame, 0, 0, effects);
    }

    Script disconnect(int port, Expected... effects) {
      return add(StepKind.Disconnect, port, null, 0, 0, effects);
    }

    Script datagram(byte[] frame, Expected... effects) {
      return add(StepKind.Datagram, LinkPort.World, frame, 0, 0, effects);
    }

    Script advance(int millis, Expected... effects) {
      return add(StepKind.Advance, LinkPort.World, null, millis, 0, effects);
    }

    Script serve(int count, Expected... effects) {
      return add(StepKind.Serve, LinkPort.World, null, 0, count, effects);
    }

    Script heartbeat(Expected... effects) {
      return add(StepKind.Heartbeat, LinkPort.World, null, 0, 0, effects);
    }

    private Script add(
        int kind, int port, byte[] frame, int millis, int count, Expected[] effects) {
      steps.add(new Spec(kind, port, frame, millis, count, effects));
      return this;
    }

    int encode(FlatBufferBuilder builder) {
      int[] offsets = new int[steps.size()];
      for (int i = 0; i < steps.size(); i++) {
        Spec spec = steps.get(i);
        int[] effects = new int[spec.effects().length];
        for (int e = 0; e < effects.length; e++) {
          effects[e] = spec.effects()[e].encode(builder);
        }
        int effectVector = Step.createEffectsVector(builder, effects);
        int frame = spec.frame() == null ? 0 : Step.createFrameVector(builder, spec.frame());
        offsets[i] =
            Step.createStep(
                builder,
                spec.kind(),
                spec.port(),
                frame,
                spec.millis(),
                spec.count(),
                effectVector);
      }
      int stepVector = ConformanceCase.createStepsVector(builder, offsets);
      return ConformanceCase.createConformanceCase(builder, builder.createString(name), stepVector);
    }
  }
}
