#pragma once

#include <array>
#include <chrono>
#include <cstdint>
#include <memory>
#include <span>
#include <string>
#include <string_view>
#include <unordered_map>

#include <asio/io_context.hpp>
#include <asio/ip/tcp.hpp>
#include <asio/ip/udp.hpp>
#include <asio/steady_timer.hpp>
#include <flatbuffers/flatbuffers.h>

#include "maestro/search/profile.hpp"
#include "maestro/serve/request_queue.hpp"
#include "maestro/serve/workers.hpp"
#include "maestro/world/world_store.hpp"
#include "pathfinding_generated.h"

namespace maestro::net {

struct ServerOptions {
  /// Address every socket binds to.
  std::string Bind = "0.0.0.0";
  /// Port 0 picks a free one.
  std::uint16_t WorldPort = 25700;
  std::uint16_t ControlPort = 25701;
  std::uint16_t RequestPort = 25702;
  /// Search threads. With none, requests only wait, which suits tests.
  unsigned Workers = 1;
  /// The node's own heartbeat interval: 20 ticks.
  std::chrono::milliseconds HeartbeatInterval{1000};
  /// How long an answer or a cancel is kept for later copies of its request:
  /// the 10 ticks after which the controller gives up on a request.
  std::chrono::milliseconds Retention{500};
  fbs::Pathfinder Search = fbs::Pathfinder::VANILLA;
};

/// The network side of a compute node. The controller connects to two TCP
/// ports, one for the world stream and one for control, and the two
/// connections form one link. The node serves one link at a time: a new
/// connection on either port replaces the whole link, and losing either
/// connection loses it. Requests arrive over UDP, and each answer goes to the
/// address its request came from.
///
/// A link starts with a `Hello` on the control connection. Losing it forgets
/// every profile and request, since the next link may come from another
/// controller run, whose ids mean other things. The world state stays, so the
/// next link can resume it. A world frame the store refuses drops the world
/// state too, and the link with it, so the controller reconnects and sends
/// the area again.
///
/// Everything but the searches runs on the thread running the `io_context`,
/// including applying world frames.
class Server {
public:
  /// Binds every socket and starts the workers. Throws `std::system_error` if
  /// a socket cannot be bound, and `std::invalid_argument` if this build
  /// lacks the search.
  Server(asio::io_context &Io, const ServerOptions &Opts);

  Server(const Server &) = delete;
  Server &operator=(const Server &) = delete;

  ~Server();

  std::uint16_t worldPort() const { return WorldPort; }
  std::uint16_t controlPort() const { return ControlPort; }
  std::uint16_t requestPort() const { return RequestPort; }

  /// Closes every socket and stops the workers, after which the
  /// `io_context` runs out of work. Safe to call from any thread.
  void stop();

private:
  class Stream;

  void acceptWorld();
  void acceptControl();
  void receiveRequests();
  void scheduleHeartbeat();

  void onWorldFrame(std::span<const std::uint8_t> Frame);
  void onControlFrame(std::span<const std::uint8_t> Frame);
  void onDatagram(std::span<const std::uint8_t> Datagram,
                  const asio::ip::udp::endpoint &From);
  void admit(const fbs::PathRequest &Wire, const asio::ip::udp::endpoint &From,
             serve::Clock::time_point Now);

  void sendHello();
  void sendHeartbeat();
  void sendTo(const asio::ip::udp::endpoint &To,
              std::span<const std::uint8_t> Datagram);

  /// Closes both connections and forgets what belongs to the link.
  void dropLink(std::string_view Reason);
  void shutdown();

  asio::io_context &Io;
  const ServerOptions Opts;
  asio::ip::tcp::acceptor WorldAcceptor;
  asio::ip::tcp::acceptor ControlAcceptor;
  asio::ip::udp::socket Requests;
  asio::steady_timer Heartbeat;
  std::uint16_t WorldPort = 0;
  std::uint16_t ControlPort = 0;
  std::uint16_t RequestPort = 0;
  bool Stopped = false;

  std::shared_ptr<Stream> WorldLink;
  std::shared_ptr<Stream> ControlLink;

  /// The datagram being received and its sender.
  std::array<std::uint8_t, 65536> Received{};
  asio::ip::udp::endpoint Sender;

  /// Scratch for frames built on the I/O thread.
  flatbuffers::FlatBufferBuilder Builder;

  world::WorldStore Store;
  serve::RequestQueue Queue;
  /// Profiles received on the current link. Only the I/O thread uses this:
  /// a job carries its own profile to the worker.
  std::unordered_map<std::uint16_t, std::shared_ptr<const search::Profile>>
      Profiles;
  /// Last, so it stops before anything its threads use is destroyed.
  serve::Workers Pool;
};

} // namespace maestro::net
