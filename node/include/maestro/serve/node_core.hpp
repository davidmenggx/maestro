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
  /// How long an answer or a cancel is kept for later copies of its request.
  Clock::duration Retention = std::chrono::milliseconds(1000);
  /// Longest a job may wait for a worker. A later one is settled unsearched,
  /// as a canceled request is.
  Clock::duration MaxWait = std::chrono::milliseconds(1000);
  /// How long a new connection may wait before its first frame.
  Clock::duration JoinTimeout = std::chrono::seconds(5);
  fbs::Pathfinder Search = fbs::Pathfinder::VANILLA;
};

/// Largest answer the node sends: the largest UDP payload over IPv4.
inline constexpr std::size_t MaxAnswerSize = 65507;

/// A copy of `Frame` to send as an answer, or null if over `MaxAnswerSize`.
Answer answerOf(std::span<const std::uint8_t> Frame);

/// What one thread reuses from job to job.
struct Scratch {
  /// `Kind` must be a search this build has.
  explicit Scratch(fbs::Pathfinder Kind);

  std::unique_ptr<search::Search> Search;
  flatbuffers::FlatBufferBuilder Builder;
  search::Result Path;
};

/// What `NodeCore::run` did with a job.
struct Outcome {
  /// False if the job waited past `MaxWait` and was dropped unsearched.
  bool Searched = false;
  /// The answer to pass to `deliver`, or null if there is none to send.
  Answer Result = nullptr;
};

/// Every protocol decision of a compute node, with no sockets, threads or
/// clock of its own. Events go in, and what the node does comes out through
/// `Output`.
///
/// A connection is pending until its first frame, which must be a `Join`
/// naming a link `(run, link)`. The node serves one link at a time: a `Join`
/// of another run or a later link replaces it, the current link joins once per
/// port, and anything else is closed alone. Losing either joined connection
/// loses the link, and a dropped link's token is not accepted again.
///
/// A link starts with a `Hello` on its control connection. Losing it forgets
/// every profile and request, but the world state stays so the next link can
/// resume it. A refused world frame, or an assignment of another run than the
/// link's, drops the world state and the link, so the controller reconnects
/// and sends the area again.
///
/// A job is answered only if it is searched and the answer fits a datagram.
/// Otherwise it settles with no answer, and later copies are dropped.
///
/// Every member but `queue`, `run` and `searchKind` belongs to the event
/// thread, which `Output` is also called on. Those three are thread-safe.
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

  RequestQueue &queue() { return Queue; }

  const world::WorldStore &store() const { return Store; }

  /// Searches for `Next`, which `queue().take` returned, unless it waited past
  /// `MaxWait`, and settles it.
  Outcome run(const Job &Next, Scratch &With);

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
};

} // namespace maestro::serve
