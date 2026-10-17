#pragma once

#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <optional>
#include <span>
#include <string_view>
#include <unordered_map>

#include <asio/ip/udp.hpp>
#include <flatbuffers/flatbuffers.h>

#include "maestro/search/profile.hpp"
#include "maestro/search/search.hpp"
#include "maestro/serve/request_queue.hpp"
#include "maestro/world/world_store.hpp"
#include "pathfinding_generated.h"

namespace maestro::serve {

/// The node's two TCP ports, which carry one link between them.
enum class Port { World, Control };

/// Names one TCP connection. The caller of `NodeCore::connected` picks it.
using ConnectionId = std::uint64_t;

struct NodeOptions {
  /// How long an answer or a cancel is kept for later copies of its request:
  /// the 10 ticks after which the controller gives up on a request.
  Clock::duration Retention = std::chrono::milliseconds(500);
  /// How long a new connection may wait before its first frame.
  Clock::duration JoinTimeout = std::chrono::seconds(5);
  fbs::Pathfinder Search = fbs::Pathfinder::VANILLA;
};

/// What one thread reuses from job to job.
struct Scratch {
  /// `Kind` must be a search this build has.
  explicit Scratch(fbs::Pathfinder Kind);

  std::unique_ptr<search::Search> Search;
  flatbuffers::FlatBufferBuilder Builder;
  search::Result Path;
};

/// Every protocol decision of a compute node, with no sockets, threads or
/// clock of its own. Events go in, and what the node does comes out through
/// `Output`.
///
/// The controller connects to the world and control ports, and the two
/// connections form one link. A connection is pending until its first frame,
/// which must be a `Join` naming the link it belongs to. The node keeps the
/// newest link token it accepted, `(run, link)`, and serves one link at a
/// time: a `Join` of another run or a later link replaces the link, one of
/// the current link joins it once per port, and anything else is refused by
/// closing that connection alone. Losing either joined connection loses the
/// link, and a dropped link's token is not accepted again.
///
/// A link starts with a `Hello` on its control connection. Losing it forgets
/// every profile and request, since the next link may come from another
/// controller run, whose ids mean other things. The world state stays, so the
/// next link can resume it. A world frame the store refuses drops the world
/// state too, and the link with it, so the controller reconnects and sends the
/// area again.
///
/// Every member but `queue` and `run` must be called from one thread, the
/// event thread, which is also the thread `Output` is called on. `queue` and
/// `run` may be called from any thread.
class NodeCore {
public:
  class Output {
  public:
    virtual ~Output() = default;

    virtual void send(ConnectionId To, std::span<const std::uint8_t> Frame) = 0;

    /// Closes `Which`. The node reports no `disconnected` for it.
    virtual void close(ConnectionId Which) = 0;

    virtual void sendTo(const asio::ip::udp::endpoint &To,
                        std::span<const std::uint8_t> Datagram) = 0;

    /// The link was dropped, after every connection of it was closed.
    virtual void linkDropped(std::string_view Reason) = 0;
  };

  /// The current time. Called from worker threads too.
  using TimeSource = std::function<Clock::time_point()>;

  /// Throws `std::invalid_argument` if this build lacks `Opts.Search`.
  NodeCore(const NodeOptions &Opts, TimeSource Now, Output &Out);

  NodeCore(const NodeCore &) = delete;
  NodeCore &operator=(const NodeCore &) = delete;

  /// A connection opened on `On`.
  void connected(Port On, ConnectionId Id);

  /// One frame arrived on `From`.
  void received(ConnectionId From, std::span<const std::uint8_t> Frame);

  /// The peer or the network closed `Id`.
  void disconnected(ConnectionId Id);

  void datagram(std::span<const std::uint8_t> Frame,
                const asio::ip::udp::endpoint &From);

  /// The heartbeat timer fired.
  void heartbeat();

  /// Closes every connection that has sent no frame within `JoinTimeout` of
  /// opening.
  void expirePending();

  /// Runs `Count` waiting jobs on the event thread, oldest first, or every
  /// waiting job if `Count` is 0, and sends their answers. Returns how many
  /// ran.
  std::size_t serveWaiting(std::size_t Count);

  RequestQueue &queue() { return Queue; }

  const world::WorldStore &store() const { return Store; }

  /// Searches for `Next`, which `queue().take` returned, and settles it.
  /// Returns the answer to `deliver`, or null if there is none to send.
  Answer run(const Job &Next, Scratch &With);

  /// Sends `Result`, the answer to `Finished`, unless the link it came on has
  /// been dropped since.
  void deliver(const Job &Finished, const Answer &Result);

  fbs::Pathfinder searchKind() const { return Opts.Search; }

private:
  struct Token {
    std::uint64_t Run = 0;
    std::uint64_t Link = 0;

    friend bool operator==(Token, Token) = default;
  };

  struct Pending {
    Port On;
    Clock::time_point Since;
  };

  void join(Port On, ConnectionId Id, Token Named);
  void onWorldFrame(std::span<const std::uint8_t> Frame);
  void onControlFrame(std::span<const std::uint8_t> Frame);
  void admit(const fbs::PathRequest &Wire, const asio::ip::udp::endpoint &From,
             std::uint64_t Run, std::uint32_t RegionId, Clock::time_point At);
  void sendHello(ConnectionId To);
  /// Closes the link's connections and forgets what belongs to the link.
  void dropLink(std::string_view Reason);

  std::optional<ConnectionId> &joined(Port On) {
    return Joined[static_cast<std::size_t>(On)];
  }

  const NodeOptions Opts;
  const TimeSource Now;
  Output &Out;

  world::WorldStore Store;
  RequestQueue Queue;

  /// The newest link token accepted, which stays after the link drops.
  Token Current;
  bool Live = false;
  /// The link's connection on each port, once it has joined.
  std::array<std::optional<ConnectionId>, 2> Joined;
  /// Connections that have sent no frame yet.
  std::map<ConnectionId, Pending> Waiting;

  /// Profiles received on the current link. A job carries its own to the
  /// worker.
  std::unordered_map<std::uint16_t, std::shared_ptr<const search::Profile>>
      Profiles;
  /// Scratch for frames built on the event thread.
  flatbuffers::FlatBufferBuilder Builder;
  /// For `serveWaiting`.
  Scratch Inline;
};

} // namespace maestro::serve
