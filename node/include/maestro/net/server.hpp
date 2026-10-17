#pragma once

#include <array>
#include <chrono>
#include <cstdint>
#include <map>
#include <memory>
#include <span>
#include <string>
#include <string_view>

#include <asio/io_context.hpp>
#include <asio/ip/tcp.hpp>
#include <asio/ip/udp.hpp>
#include <asio/steady_timer.hpp>

#include "maestro/serve/node_core.hpp"
#include "maestro/serve/workers.hpp"
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
  /// How long a new connection may wait before its first frame.
  std::chrono::milliseconds JoinTimeout{5000};
  fbs::Pathfinder Search = fbs::Pathfinder::VANILLA;
};

/// The network side of a compute node: two TCP acceptors, one for the world
/// stream and one for control, a UDP socket for requests, the heartbeat
/// timer, and the search threads, all reporting to a `serve::NodeCore`, which
/// makes every decision. Each answer goes to the address its request came
/// from.
///
/// Everything but the searches runs on the thread running the `io_context`,
/// including applying world frames.
class Server : private serve::NodeCore::Output {
public:
  /// Binds every socket and starts the workers. Throws `std::system_error` if
  /// a socket cannot be bound, and `std::invalid_argument` if this build
  /// lacks the search.
  Server(asio::io_context &Io, const ServerOptions &Opts);

  Server(const Server &) = delete;
  Server &operator=(const Server &) = delete;

  ~Server() override;

  std::uint16_t worldPort() const { return WorldPort; }
  std::uint16_t controlPort() const { return ControlPort; }
  std::uint16_t requestPort() const { return RequestPort; }

  /// Closes every socket and stops the workers, after which the
  /// `io_context` runs out of work. Safe to call from any thread.
  void stop();

private:
  class Stream;

  void accept(asio::ip::tcp::acceptor &Acceptor, serve::Port On);
  void open(asio::ip::tcp::socket Socket, serve::Port On);
  void receiveRequests();
  void scheduleHeartbeat();
  void shutdown();

  void send(serve::ConnectionId To,
            std::span<const std::uint8_t> Frame) override;
  void close(serve::ConnectionId Which) override;
  void sendTo(const asio::ip::udp::endpoint &To,
              std::span<const std::uint8_t> Datagram) override;
  void linkDropped(std::string_view Reason) override;

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

  /// Every open connection, joined or not.
  std::map<serve::ConnectionId, std::shared_ptr<Stream>> Connections;
  serve::ConnectionId LastConnection = 0;

  /// The datagram being received and its sender.
  std::array<std::uint8_t, 65536> Received{};
  asio::ip::udp::endpoint Sender;

  serve::NodeCore Core;
  /// Last, so it stops before anything its threads use is destroyed.
  serve::Workers Pool;
};

} // namespace maestro::net
