# Node networking, assignment sequence, and request cancels

The compute node now runs as a network service. It listens on two TCP ports, one for the world
stream and one for control, and one UDP port for requests. The two TCP connections form one link:
on connect the node sends `Hello`, applies world frames as they arrive, registers profiles, sends
a heartbeat with its queue depth every second, and answers requests from a pool of search
threads. A request it has answered is answered again with the same bytes, and one it cannot serve
for lack of a profile gets `UnknownProfile` at once. Two wire changes come with it. A
`RegionAssignment` now names the sequence the stream stands at, so the node refuses a first frame
that does not follow it. A new `PathCancel` message tells a node to drop requests whose mobs no
longer want them. The controller sends it, and the modeled node in the DST handles it the same way
the real node does.

## Changes

- Schema:
  - `RegionAssignment.sequence`: where the stream stands. A fresh area is all air as of it, and a
    resume repeats the `last_sequence` the node reported. Old fixtures stay valid, since a field
    left at 0 is not written.
  - `PathCancel { request_ids }`, added to the `RequestMsg` union.
- Node, world (`WorldStore`, `Area`): an assignment creates the area at its sequence, and every
  frame must be the next one, the first included. `AwaitingBaseline` is gone. A resume must also
  name the held sequence.
- Node, requests (`serve::RequestQueue`, `serve::Workers`):
  - A FIFO with one entry per open request id. A copy of a waiting or running request is dropped.
  - Answers and cancels are kept for `Retention` (500 ms). A later copy of an answered request
    gets the cached bytes, and one of a canceled request is dropped.
  - A cancel drops a waiting request, and is remembered if the request has not arrived. A running
    or answered request is left alone.
  - `clear` forgets everything when the link goes. A job still running then settles without effect
    and its answer is not sent.
  - Each worker owns its search and builder, pins the current area per job, and answers with no
    path at sequence 0 when no area is held.
- Node, network (`net::Server`, `main.cpp`):
  - Two TCP acceptors and one UDP socket on asio, one I/O thread. World frames are applied on it.
  - A new connection on either port replaces the whole link. Losing either connection, a refused
    world frame, a malformed control frame, a profile that breaks the contract, or a frame header
    past `MaxPayloadSize` drops the link. Dropping the link forgets profiles and requests and
    keeps the world, except after a refused world frame, where the store already dropped it.
  - Unknown control and request bodies, and malformed datagrams, are ignored.
  - Answers go to the address the request came from, sent from the I/O thread. The UDP socket is
    non-blocking, so a full send buffer drops an answer instead of stalling.
  - `Hello` reports nothing held while the held sequence is 0, as the model does.
  - The heartbeat depth counts waiting and running requests.
  - CLI: `--bind`, `--world-port` (25700), `--control-port` (25701), `--request-port` (25702),
    `--workers` (one per core), `--otlp`. SIGINT and SIGTERM stop the node cleanly.
- Controller (`Frames`, `ProbeController`):
  - `Frames.assignment` takes the sequence. A fresh assignment stands at the region's last
    sequence, a resume at the node's.
  - `Frames.cancels`, at most `MAX_CANCELS_PER_FRAME` (128) ids, which fits 1200 bytes.
  - `abandon` queues a cancel when a copy of the request has left. Cancels leave with the tick's
    request frames, in the same offload job, counted as `path.cancels`. The request still counts
    against the node until its answer is due or arrives.
  - `heldSequence` is now the region's last sequence, since a node holding only its assignment
    stands there too.
- DST:
  - `ModeledNode` takes the sequence from the assignment, refuses a resume at another sequence,
    handles `PathCancel` like the real node, and forgets profiles and requests on `linkDown`, which
    `SimTransport.resetLink` and `kill` call.
  - The coverage tier checks that a cancel reached a request still waiting on its node.
- Tests:
  - C++: `test_request_queue.cpp` (11 tests, one threaded) and `test_server.cpp` (14 loopback tests:
    an exact parity case served over real sockets, retries, `UnknownProfile`, a refused frame,
    resume after reconnect, profiles forgotten with the link, heartbeat depth and cancels, link
    replacement on each port, oversized and malformed frames, malformed datagrams, stopping with
    work queued). `WorldStore` tests cover the first frame and a resume at the wrong sequence.
  - Java: cancel frames, the assignment sequence, the model's first-frame check, cancels and link
    reset, and cancels under saturation.
- CLAUDE.md: the link and its ports, the assignment sequence, and cancels.

## Results

- 137 node tests pass, also under ASan with UBSan and under TSan. The server and queue tests
  passed 10 more TSan runs. The parity test still matches every exact case, and the loopback test
  serves an exact case over real sockets with vanilla's answer.
- 129 controller and 21 mod tests pass, and the dev mod compiles. The fixtures are unchanged and
  still rebuild byte for byte.
- A parallel scan of every CI seed range (seeds 1 to 100000 of the reliable, lossy, churn and
  fuzzed runs, 400000 runs) found no violation. 9738 fuzzed runs were held to the SLO, 4794 of
  them at the latency edge. At 5% loss each way, 4079 of about 10.4 million requests timed out,
  against 4262 before. The coverage tier passes.
- Saturated scenario, seeds 1 to 20: 5829 answered and no timeouts, against 1517 before. Nodes
  answered 4111 requests after their mobs had moved on, against 8321 before, and dropped 5040
  canceled ones unserved. The nodes did about the same amount of work, but more of it was wanted.
- The `maestro-node` binary binds its three ports, refuses bad flags, and exits cleanly on SIGTERM.

## Design decisions made without instruction

**The link is the pair of TCP connections, and the newest connection wins.** With separate ports
the node cannot tell which world connection belongs to which control connection, so a new
connection on either port replaces both. A stale connection from an aborted connect can pair with a
new one, but the next replacement or loss resets the link and the controller connects again.
Alternatives: a one-byte tag naming the controller's link, or refusing new connections while a
link is up, which blocks a controller that restarted behind a half-open connection.

**Profiles and request ids are forgotten with the link, and the world is kept.** The next link may
come from a new controller run, whose class ids and request ids mean other things, so a cached
answer or profile could be wrong. The controller already drops every request on a disconnect and
sends profiles again on each connection, so nothing is lost. The model does the same, so the DST
checks it. Alternative: keep them, as the model did before, which is only safe within one run.

**Answers and cancels are kept for 500 ms.** The model keeps answers for the whole run, which a
real node cannot. 500 ms is the 10 ticks after which the controller gives up on a request, so no
copy it sends later can need the answer. The heartbeat uses the model's 20 ticks, 1 s.

**A cancel is one-way and best effort.** It travels on the UDP request stream and has no reply.
The controller keeps counting a canceled request against the node until its answer is due, since
the cancel may be lost or arrive after the node started the request. Admission is therefore
conservative under saturation. Alternative: an acknowledged cancel that releases the request at
once, at the cost of one more reply type and datagram.

**Only requests a mob no longer wants are canceled.** Superseded, canceled and mob-gone requests
are, timeouts are not: admission keeps a node's work within the 10-tick budget, so a timed-out
request is almost always lost or done.

**A node with no area answers with no path at sequence 0.** The controller already drops an
answer older than the request's start chunk, so this reaches the same outcome without a special
case. Alternative: drop the request silently and let it time out.

**A bad control frame drops the link, and unknown messages are ignored.** A malformed frame or a
profile out of bounds means the controller and node disagree, so the link is reset, which keeps
the world and lets the controller resume. A well-formed message of an unknown type is ignored,
so a newer controller can add messages.

**World frames are applied on the I/O thread.** A chunk decode takes well under a millisecond, so
a separate world thread was not worth the extra synchronization.

## Not done

- No test runs the Java controller against the real node yet. That needs the Netty transport of
  the next phase.
- The node exports no per-request spans or metrics yet.
- The two numeric parity gaps (collision tolerance, sweep maths in relative coordinates) stay
  deferred.
