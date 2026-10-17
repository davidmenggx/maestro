#include "maestro/net/server.hpp"

#include <deque>
#include <functional>
#include <iostream>
#include <utility>
#include <vector>

#include <asio/buffer.hpp>
#include <asio/post.hpp>
#include <asio/read.hpp>
#include <asio/write.hpp>

#include "maestro/net/framing.hpp"

namespace maestro::net {

namespace {

using asio::ip::tcp;
using asio::ip::udp;

serve::NodeOptions nodeOptions(const ServerOptions &Opts) {
  return {.Retention = Opts.Retention,
          .JoinTimeout = Opts.JoinTimeout,
          .Search = Opts.Search};
}

} // namespace

/// One TCP connection carrying length-prefixed frames. Lives on the I/O
/// thread. `OnClose` runs when the peer or the network ends the connection,
/// not when `close` does. `OnSilent` runs if no frame arrives within the
/// timeout `start` is given.
class Server::Stream : public std::enable_shared_from_this<Stream> {
public:
  using FrameHandler = std::function<void(std::span<const std::uint8_t>)>;
  using Handler = std::function<void()>;

  explicit Stream(tcp::socket Socket)
      : Socket(std::move(Socket)), Deadline(this->Socket.get_executor()) {}

  void start(FrameHandler OnFrame, Handler OnClose,
             std::chrono::milliseconds Timeout, Handler OnSilent) {
    this->OnFrame = std::move(OnFrame);
    this->OnClose = std::move(OnClose);
    Deadline.expires_after(Timeout);
    Deadline.async_wait(
        [Self = shared_from_this(),
         OnSilent = std::move(OnSilent)](asio::error_code Error) {
          if (!Error && Self->open())
            OnSilent();
        });
    readHeader();
  }

  bool open() const { return Socket.is_open(); }

  /// Queues `Payload` as one frame.
  void send(std::span<const std::uint8_t> Payload) {
    if (!open())
      return;
    Outbox.push_back(frame(Payload));
    if (Outbox.size() == 1)
      writeNext();
  }

  void close() {
    Deadline.cancel();
    asio::error_code Ignored;
    Socket.close(Ignored);
  }

private:
  void readHeader() {
    asio::async_read(
        Socket, asio::buffer(Header),
        [Self = shared_from_this()](asio::error_code Error, std::size_t) {
          if (Error)
            return Self->fail();
          const auto Peeked = peekHeader(Self->Header);
          if (Peeked.Status != HeaderStatus::Ready)
            return Self->fail();
          Self->Body.resize(Peeked.Length);
          Self->readBody();
        });
  }

  void readBody() {
    asio::async_read(
        Socket, asio::buffer(Body),
        [Self = shared_from_this()](asio::error_code Error, std::size_t) {
          if (Error)
            return Self->fail();
          Self->Deadline.cancel();
          Self->OnFrame(Self->Body);
          // The handler may have closed this connection.
          if (Self->open())
            Self->readHeader();
        });
  }

  void writeNext() {
    asio::async_write(
        Socket, asio::buffer(Outbox.front()),
        [Self = shared_from_this()](asio::error_code Error, std::size_t) {
          if (Error)
            return Self->fail();
          Self->Outbox.pop_front();
          if (!Self->Outbox.empty())
            Self->writeNext();
        });
  }

  /// A read or write failed: the peer left, the network broke, or the peer
  /// declared a frame past the limit.
  void fail() {
    if (!open())
      return;
    close();
    OnClose();
  }

  tcp::socket Socket;
  asio::steady_timer Deadline;
  std::array<std::uint8_t, HeaderSize> Header{};
  std::vector<std::uint8_t> Body;
  std::deque<std::vector<std::uint8_t>> Outbox;
  FrameHandler OnFrame;
  Handler OnClose;
};

Server::Server(asio::io_context &Io, const ServerOptions &Opts)
    : Io(Io), Opts(Opts),
      WorldAcceptor(Io, {asio::ip::make_address(Opts.Bind), Opts.WorldPort}),
      ControlAcceptor(Io,
                      {asio::ip::make_address(Opts.Bind), Opts.ControlPort}),
      Requests(Io, {asio::ip::make_address(Opts.Bind), Opts.RequestPort}),
      Heartbeat(Io),
      Core(
          nodeOptions(Opts), [] { return serve::Clock::now(); }, *this),
      Pool(Core, Opts.Workers,
           [this](const serve::Job &Finished, serve::Answer Result) {
             asio::post(this->Io, [this, Finished, Result = std::move(Result)] {
               Core.deliver(Finished, Result);
             });
           }) {
  WorldPort = WorldAcceptor.local_endpoint().port();
  ControlPort = ControlAcceptor.local_endpoint().port();
  RequestPort = Requests.local_endpoint().port();
  // A full send buffer drops the answer, as the network might, instead of
  // stalling the I/O thread.
  Requests.non_blocking(true);
  accept(WorldAcceptor, serve::Port::World);
  accept(ControlAcceptor, serve::Port::Control);
  receiveRequests();
  scheduleHeartbeat();
}

Server::~Server() { Pool.stop(); }

void Server::stop() {
  asio::post(Io, [this] { shutdown(); });
}

void Server::shutdown() {
  Stopped = true;
  asio::error_code Ignored;
  WorldAcceptor.close(Ignored);
  ControlAcceptor.close(Ignored);
  Requests.close(Ignored);
  Heartbeat.cancel();
  for (auto &[Id, Connection] : Connections)
    Connection->close();
  Connections.clear();
  Pool.stop();
}

void Server::accept(tcp::acceptor &Acceptor, serve::Port On) {
  Acceptor.async_accept(
      [this, &Acceptor, On](asio::error_code Error, tcp::socket Socket) {
        if (!Acceptor.is_open())
          return;
        if (!Error)
          open(std::move(Socket), On);
        accept(Acceptor, On);
      });
}

void Server::open(tcp::socket Socket, serve::Port On) {
  asio::error_code Ignored;
  Socket.set_option(tcp::no_delay(true), Ignored);
  const serve::ConnectionId Id = ++LastConnection;
  auto Connection = std::make_shared<Stream>(std::move(Socket));
  Connections.emplace(Id, Connection);
  Core.connected(On, Id);
  Connection->start(
      [this, Id](std::span<const std::uint8_t> Frame) {
        Core.received(Id, Frame);
      },
      [this, Id] {
        Connections.erase(Id);
        Core.disconnected(Id);
      },
      Opts.JoinTimeout, [this] { Core.expirePending(); });
}

void Server::receiveRequests() {
  Requests.async_receive_from(asio::buffer(Received), Sender,
                              [this](asio::error_code Error, std::size_t Size) {
                                if (!Requests.is_open())
                                  return;
                                // An error here is about an earlier send, such
                                // as an ICMP unreachable, and the socket still
                                // works.
                                if (!Error)
                                  Core.datagram({Received.data(), Size},
                                                Sender);
                                receiveRequests();
                              });
}

void Server::scheduleHeartbeat() {
  Heartbeat.expires_after(Opts.HeartbeatInterval);
  Heartbeat.async_wait([this](asio::error_code Error) {
    // A wait that finished just before `shutdown` is not canceled by it.
    if (Error || Stopped)
      return;
    Core.heartbeat();
    scheduleHeartbeat();
  });
}

void Server::send(serve::ConnectionId To, std::span<const std::uint8_t> Frame) {
  if (const auto Found = Connections.find(To); Found != Connections.end())
    Found->second->send(Frame);
}

void Server::close(serve::ConnectionId Which) {
  if (const auto Found = Connections.find(Which); Found != Connections.end()) {
    Found->second->close();
    Connections.erase(Found);
  }
}

void Server::sendTo(const udp::endpoint &To,
                    std::span<const std::uint8_t> Datagram) {
  asio::error_code Ignored;
  Requests.send_to(asio::buffer(Datagram.data(), Datagram.size()), To, 0,
                   Ignored);
}

void Server::linkDropped(std::string_view Reason) {
  std::clog << "maestro-node: link dropped: " << Reason << '\n';
}

} // namespace maestro::net
