#include "maestro/net/server.hpp"

#include <deque>
#include <functional>
#include <iostream>
#include <stdexcept>
#include <utility>
#include <vector>

#include <asio/buffer.hpp>
#include <asio/post.hpp>
#include <asio/read.hpp>
#include <asio/write.hpp>

#include "control_generated.h"
#include "maestro/net/framing.hpp"

namespace maestro::net {

namespace {

using asio::ip::tcp;
using asio::ip::udp;

fbs::Pathfinder checkedSearch(fbs::Pathfinder Kind) {
  if (!search::makeSearch(Kind))
    throw std::invalid_argument("this build has no such search");
  return Kind;
}

const char *describe(world::ApplyStatus Status) {
  switch (Status) {
  case world::ApplyStatus::Applied:
    return "applied";
  case world::ApplyStatus::Malformed:
    return "malformed";
  case world::ApplyStatus::Unassigned:
    return "before any assignment";
  case world::ApplyStatus::OutOfSequence:
    return "out of sequence";
  case world::ApplyStatus::CannotResume:
    return "resume of a stream not held";
  }
  return "refused";
}

template <typename T> const T *verified(std::span<const std::uint8_t> Frame) {
  flatbuffers::Verifier Verifier(Frame.data(), Frame.size());
  if (!Verifier.VerifyBuffer<T>(nullptr))
    return nullptr;
  return flatbuffers::GetRoot<T>(Frame.data());
}

} // namespace

/// One TCP connection carrying length-prefixed frames. Lives on the I/O
/// thread. `OnClose` runs when the peer or the network ends the connection,
/// not when `close` does.
class Server::Stream : public std::enable_shared_from_this<Stream> {
public:
  using FrameHandler =
      std::function<void(Stream &, std::span<const std::uint8_t>)>;
  using CloseHandler = std::function<void(Stream &)>;

  explicit Stream(tcp::socket Socket) : Socket(std::move(Socket)) {}

  void start(FrameHandler OnFrame, CloseHandler OnClose) {
    this->OnFrame = std::move(OnFrame);
    this->OnClose = std::move(OnClose);
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
          Self->OnFrame(*Self, Self->Body);
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
    OnClose(*this);
  }

  tcp::socket Socket;
  std::array<std::uint8_t, HeaderSize> Header{};
  std::vector<std::uint8_t> Body;
  std::deque<std::vector<std::uint8_t>> Outbox;
  FrameHandler OnFrame;
  CloseHandler OnClose;
};

Server::Server(asio::io_context &Io, const ServerOptions &Opts)
    : Io(Io), Opts(Opts),
      WorldAcceptor(Io, {asio::ip::make_address(Opts.Bind), Opts.WorldPort}),
      ControlAcceptor(Io,
                      {asio::ip::make_address(Opts.Bind), Opts.ControlPort}),
      Requests(Io, {asio::ip::make_address(Opts.Bind), Opts.RequestPort}),
      Heartbeat(Io), Queue(Opts.Retention),
      Pool(Queue, Store, checkedSearch(Opts.Search), Opts.Workers,
           [this](const udp::endpoint &To, serve::Answer Result) {
             asio::post(this->Io, [this, To, Result = std::move(Result)] {
               sendTo(To, *Result);
             });
           }) {
  WorldPort = WorldAcceptor.local_endpoint().port();
  ControlPort = ControlAcceptor.local_endpoint().port();
  RequestPort = Requests.local_endpoint().port();
  // A full send buffer drops the answer, as the network might, instead of
  // stalling the I/O thread.
  Requests.non_blocking(true);
  acceptWorld();
  acceptControl();
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
  if (WorldLink)
    WorldLink->close();
  if (ControlLink)
    ControlLink->close();
  WorldLink.reset();
  ControlLink.reset();
  Pool.stop();
}

void Server::acceptWorld() {
  WorldAcceptor.async_accept(
      [this](asio::error_code Error, tcp::socket Socket) {
        if (!WorldAcceptor.is_open())
          return;
        if (!Error) {
          if (WorldLink)
            dropLink("a new world connection replaced the link");
          Socket.set_option(tcp::no_delay(true), Error);
          WorldLink = std::make_shared<Stream>(std::move(Socket));
          WorldLink->start(
              [this](Stream &From, std::span<const std::uint8_t> Frame) {
                if (&From == WorldLink.get())
                  onWorldFrame(Frame);
              },
              [this](Stream &From) {
                if (&From == WorldLink.get())
                  dropLink("the world connection closed");
              });
        }
        acceptWorld();
      });
}

void Server::acceptControl() {
  ControlAcceptor.async_accept(
      [this](asio::error_code Error, tcp::socket Socket) {
        if (!ControlAcceptor.is_open())
          return;
        if (!Error) {
          if (ControlLink)
            dropLink("a new control connection replaced the link");
          Socket.set_option(tcp::no_delay(true), Error);
          ControlLink = std::make_shared<Stream>(std::move(Socket));
          ControlLink->start(
              [this](Stream &From, std::span<const std::uint8_t> Frame) {
                if (&From == ControlLink.get())
                  onControlFrame(Frame);
              },
              [this](Stream &From) {
                if (&From == ControlLink.get())
                  dropLink("the control connection closed");
              });
          sendHello();
        }
        acceptControl();
      });
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
                                  onDatagram({Received.data(), Size}, Sender);
                                receiveRequests();
                              });
}

void Server::scheduleHeartbeat() {
  Heartbeat.expires_after(Opts.HeartbeatInterval);
  Heartbeat.async_wait([this](asio::error_code Error) {
    // A wait that finished just before `shutdown` is not canceled by it.
    if (Error || Stopped)
      return;
    if (ControlLink)
      sendHeartbeat();
    scheduleHeartbeat();
  });
}

void Server::onWorldFrame(std::span<const std::uint8_t> Frame) {
  const world::ApplyStatus Status = Store.apply(Frame);
  if (Status != world::ApplyStatus::Applied)
    dropLink(std::string("refused a world frame: ") + describe(Status) +
             ", so all world state was dropped");
}

void Server::onControlFrame(std::span<const std::uint8_t> Frame) {
  const auto *Root = verified<fbs::ControlFrame>(Frame);
  if (!Root)
    return dropLink("a malformed control frame");
  switch (Root->body_type()) {
  case fbs::ControlMsg::MobClass: {
    const auto *Body = Root->body_as_MobClass();
    std::optional<search::Profile> Profile =
        Body ? search::Profile::decode(*Body) : std::nullopt;
    if (!Profile)
      return dropLink("a profile that breaks the contract");
    const std::uint16_t ClassId = Profile->ClassId;
    Profiles.insert_or_assign(
        ClassId, std::make_shared<const search::Profile>(*Profile));
    return;
  }
  default:
    // A message this build does not know, which a newer controller may send.
    return;
  }
}

void Server::onDatagram(std::span<const std::uint8_t> Datagram,
                        const udp::endpoint &From) {
  const auto *Root = verified<fbs::RequestFrame>(Datagram);
  if (!Root)
    return;
  const serve::Clock::time_point Now = serve::Clock::now();
  switch (Root->body_type()) {
  case fbs::RequestMsg::PathRequestBatch:
    if (const auto *Batch = Root->body_as_PathRequestBatch();
        Batch && Batch->requests())
      for (const fbs::PathRequest *Wire : *Batch->requests())
        admit(*Wire, From, Now);
    return;
  case fbs::RequestMsg::PathCancel:
    if (const auto *Cancel = Root->body_as_PathCancel();
        Cancel && Cancel->request_ids())
      for (const std::uint64_t RequestId : *Cancel->request_ids())
        Queue.cancel(RequestId, Now);
    return;
  default:
    return;
  }
}

void Server::admit(const fbs::PathRequest &Wire, const udp::endpoint &From,
                   serve::Clock::time_point Now) {
  const auto Found = Profiles.find(Wire.class_id());
  if (Found == Profiles.end()) {
    search::encodeUnknownProfile(Builder, Wire.request_id(), Wire.class_id());
    sendTo(From, {Builder.GetBufferPointer(), Builder.GetSize()});
    return;
  }
  std::optional<search::Request> Call = search::Request::decode(Wire);
  if (!Call)
    return;
  serve::Answer Cached;
  const serve::Admission Admitted =
      Queue.admit({Wire.request_id(), Found->second, *Call, From}, Now, Cached);
  if (Admitted == serve::Admission::Answered)
    sendTo(From, *Cached);
}

void Server::sendHello() {
  // Sequence 0 means nothing held, whatever area the node was assigned.
  const auto Held = Store.pin();
  const bool Holds = Held && Held->sequence() > 0;
  Builder.Clear();
  const auto Body = fbs::CreateHello(Builder, Holds ? Held->run() : 0,
                                     Holds ? Held->regionId() : 0,
                                     Holds ? Held->sequence() : 0, Opts.Search);
  Builder.Finish(fbs::CreateControlReplyFrame(
      Builder, fbs::ControlReplyMsg::Hello, Body.Union()));
  ControlLink->send({Builder.GetBufferPointer(), Builder.GetSize()});
}

void Server::sendHeartbeat() {
  Builder.Clear();
  const auto Body =
      fbs::CreateHeartbeat(Builder, static_cast<std::uint32_t>(Queue.depth()));
  Builder.Finish(fbs::CreateControlReplyFrame(
      Builder, fbs::ControlReplyMsg::Heartbeat, Body.Union()));
  ControlLink->send({Builder.GetBufferPointer(), Builder.GetSize()});
}

void Server::sendTo(const udp::endpoint &To,
                    std::span<const std::uint8_t> Datagram) {
  asio::error_code Ignored;
  Requests.send_to(asio::buffer(Datagram.data(), Datagram.size()), To, 0,
                   Ignored);
}

void Server::dropLink(std::string_view Reason) {
  std::clog << "maestro-node: link dropped: " << Reason << '\n';
  if (WorldLink)
    WorldLink->close();
  if (ControlLink)
    ControlLink->close();
  WorldLink.reset();
  ControlLink.reset();
  Profiles.clear();
  Queue.clear();
}

} // namespace maestro::net
