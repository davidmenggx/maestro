#pragma once

#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <optional>
#include <unordered_map>
#include <utility>
#include <vector>

#include <asio/ip/udp.hpp>

#include "maestro/search/profile.hpp"
#include "maestro/search/search.hpp"

namespace maestro::serve {

using Clock = std::chrono::steady_clock;

/// An encoded `ResponseFrame`, shared by the retry cache and the send.
using Answer = std::shared_ptr<const std::vector<std::uint8_t>>;

/// One admitted request or probe: what the search needs, and where to answer.
struct Job {
  std::uint64_t RequestId = 0;
  /// Null for a probe, which runs no search.
  std::shared_ptr<const search::Profile> Mob;
  search::Request Call;
  asio::ip::udp::endpoint ReplyTo;
  /// The controller run and the region its request frame named.
  std::uint64_t Run = 0;
  std::uint32_t RegionId = 0;
  /// Both set by `RequestQueue::admit`.
  std::uint64_t Generation = 0;
  Clock::time_point Admitted{};
};

/// What `RequestQueue::admit` did with a request.
enum class Admission {
  /// Queued for a worker.
  Queued,
  /// Already waiting or running, so this copy is dropped.
  Duplicate,
  /// Already answered, and the caller sends the same bytes again.
  Answered,
  /// Canceled, so this copy is dropped.
  Canceled,
};

/// The requests a node holds: a FIFO for the workers, and every request
/// settled within the last `Retention`, so a later copy of one is answered
/// with the same bytes, or dropped if it was canceled. A request id is the
/// controller's, so it means one request only for as long as one link lasts.
///
/// Every member is safe to call from any thread.
class RequestQueue {
public:
  explicit RequestQueue(Clock::duration Retention) : Retention(Retention) {}

  /// Queues `Next` unless a copy of it is open or settled. `Cached` is set to
  /// the earlier answer when the result is `Answered`.
  Admission admit(Job Next, Clock::time_point Now, Answer &Cached);

  /// Drops `RequestId` if it is waiting, and drops any copy that arrives
  /// later. A running or answered request is left alone. Returns whether a
  /// waiting request was dropped.
  bool cancel(std::uint64_t RequestId, Clock::time_point Now);

  /// The next waiting job, blocking until there is one. Nothing once closed.
  std::optional<Job> take();

  /// The next waiting job, or nothing if none is waiting or once closed.
  std::optional<Job> tryTake();

  /// Records the answer to a job from `take`. A null `Result` means it has
  /// none, and later copies are dropped. Returns false if `clear` ran since
  /// the job was admitted, and then the answer must not be sent.
  bool settle(const Job &Finished, Answer Result, Clock::time_point Now);

  /// Whether `Finished` was admitted since the last `clear`.
  bool current(const Job &Finished) const;

  /// Requests waiting or running.
  std::size_t depth() const;

  /// Forgets every request. Waiting ones are dropped, and running ones finish
  /// but settle without effect.
  void clear();

  /// Makes every `take` return nothing, now and from then on.
  void close();

private:
  enum class State { Waiting, Running };

  struct Settled {
    Answer Result;
    Clock::time_point At;
  };

  /// Takes the next waiting job, if any. Requires `Mutex`.
  std::optional<Job> next();

  /// Records `RequestId` as settled. Requires `Mutex`.
  void record(std::uint64_t RequestId, Answer Result, Clock::time_point Now);

  /// Forgets settled requests older than `Retention`. Requires `Mutex`.
  void expire(Clock::time_point Now);

  const Clock::duration Retention;
  mutable std::mutex Mutex;
  std::condition_variable Ready;
  /// Admitted jobs in order. A canceled one stays until a worker reaches it
  /// and skips it, because its id is no longer waiting.
  std::deque<Job> Queue;
  std::unordered_map<std::uint64_t, State> Open;
  std::size_t WaitingCount = 0;
  std::size_t RunningCount = 0;
  std::unordered_map<std::uint64_t, Settled> Done;
  /// Settled ids in the order they settled, for `expire`.
  std::deque<std::pair<Clock::time_point, std::uint64_t>> DoneOrder;
  std::uint64_t Generation = 0;
  bool Closed = false;
};

} // namespace maestro::serve
