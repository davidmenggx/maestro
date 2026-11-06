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
  /// The node's own heartbeat interval: 20 ticks.
  std::chrono::milliseconds HeartbeatInterval{1000};
  /// Kernel buffer asked for each way of the request socket, so a burst of
  /// datagrams is not dropped. The kernel caps it at `net.core.rmem_max` and
  /// `net.core.wmem_max`.
  int DatagramBuffer = 8 << 20;
  serve::NodeOptions Node;
};

/// Kernel buffer sizes of a socket, as the kernel reports them.
struct SocketBuffers {
  int Receive = 0;
  int Send = 0;
};

/// The network side of a compute node: TCP acceptors for the world and control
/// streams, a UDP socket for requests, the heartbeat timer and the search
/// threads, all driving a `serve::NodeCore`. Everything but the searches runs
/// on the `io_context` thread.
class Server : private serve::NodeCore::Output {
public:
  /// Binds every socket and starts the workers. Throws `std::system_error` if
  /// a socket cannot be bound, and `std::invalid_argument` if this build
  /// lacks the search.
  Server(asio::io_context &Io, const ServerOptions &Opts);

  Server(const Server &) = delete;
  Server &operator=(const Server &) = delete;

  std::uint16_t worldPort() const { return WorldPort; }
  std::uint16_t controlPort() const { return ControlPort; }
  std::uint16_t requestPort() const { return RequestPort; }

  /// The request socket's buffers as the kernel reports them. Linux reports
  /// twice the size asked, up to twice its cap, counting its own bookkeeping.
  SocketBuffers requestBuffers();

  /// Searches run over the area held, since the node started.
  std::uint64_t searches() const { return Core.searches(); }

  /// Closes every socket and stops the workers, after which the
  /// `io_context` runs out of work. Safe to call from any thread.
  void stop();

private:
  class Stream;

  void accept(asio::ip::tcp::acceptor &Acceptor, asio::steady_timer &Backoff,
              serve::Port On);
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
  /// Each paces its acceptor after a failed accept.
  asio::steady_timer WorldBackoff;
  asio::steady_timer ControlBackoff;
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

  /// Datagrams that could not be sent, and when that was last logged. A full
  /// send buffer fails many at once, so the log is paced.
  std::uint64_t Unsent = 0;
  std::chrono::steady_clock::time_point LastUnsentLog{};

  serve::NodeCore Core;
  /// Last, so it is destroyed first, and `~Workers` stops its threads before
  /// anything they use is destroyed.
  serve::Workers Pool;
};

} // namespace maestro::net
