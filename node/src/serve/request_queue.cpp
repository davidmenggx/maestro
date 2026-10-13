#include "maestro/serve/request_queue.hpp"

namespace maestro::serve {

Admission RequestQueue::admit(Job Next, Clock::time_point Now, Answer &Cached) {
  {
    std::lock_guard Lock(Mutex);
    expire(Now);
    if (const auto Found = Done.find(Next.RequestId); Found != Done.end()) {
      if (!Found->second.Result)
        return Admission::Canceled;
      Cached = Found->second.Result;
      return Admission::Answered;
    }
    if (!Open.try_emplace(Next.RequestId, State::Waiting).second)
      return Admission::Duplicate;
    Next.Generation = Generation;
    Queue.push_back(std::move(Next));
    ++WaitingCount;
  }
  Ready.notify_one();
  return Admission::Queued;
}

bool RequestQueue::cancel(std::uint64_t RequestId, Clock::time_point Now) {
  std::lock_guard Lock(Mutex);
  expire(Now);
  if (Done.contains(RequestId))
    return false;
  const auto Found = Open.find(RequestId);
  if (Found != Open.end() && Found->second == State::Running)
    return false;
  const bool Dropped = Found != Open.end();
  if (Dropped) {
    Open.erase(Found);
    --WaitingCount;
  }
  record(RequestId, nullptr, Now);
  return Dropped;
}

std::optional<Job> RequestQueue::take() {
  std::unique_lock Lock(Mutex);
  Ready.wait(Lock, [&] { return Closed || WaitingCount > 0; });
  if (Closed)
    return std::nullopt;
  // `WaitingCount` counts ids, and each has a job in the queue, so this ends.
  for (;;) {
    Job Next = std::move(Queue.front());
    Queue.pop_front();
    const auto Found = Open.find(Next.RequestId);
    if (Found == Open.end() || Found->second != State::Waiting)
      continue;
    Found->second = State::Running;
    --WaitingCount;
    ++RunningCount;
    return Next;
  }
}

bool RequestQueue::settle(const Job &Finished, Answer Result,
                          Clock::time_point Now) {
  std::lock_guard Lock(Mutex);
  --RunningCount;
  if (Finished.Generation != Generation)
    return false;
  Open.erase(Finished.RequestId);
  expire(Now);
  record(Finished.RequestId, std::move(Result), Now);
  return true;
}

std::size_t RequestQueue::depth() const {
  std::lock_guard Lock(Mutex);
  return WaitingCount + RunningCount;
}

void RequestQueue::clear() {
  std::lock_guard Lock(Mutex);
  Queue.clear();
  Open.clear();
  WaitingCount = 0;
  Done.clear();
  DoneOrder.clear();
  ++Generation;
}

void RequestQueue::close() {
  {
    std::lock_guard Lock(Mutex);
    Closed = true;
  }
  Ready.notify_all();
}

void RequestQueue::record(std::uint64_t RequestId, Answer Result,
                          Clock::time_point Now) {
  Done.insert_or_assign(RequestId, Settled{std::move(Result), Now});
  DoneOrder.emplace_back(Now, RequestId);
}

void RequestQueue::expire(Clock::time_point Now) {
  while (!DoneOrder.empty() && DoneOrder.front().first + Retention <= Now) {
    const auto [At, RequestId] = DoneOrder.front();
    DoneOrder.pop_front();
    // A request settled again later has a newer entry of its own.
    if (const auto Found = Done.find(RequestId);
        Found != Done.end() && Found->second.At == At)
      Done.erase(Found);
  }
}

} // namespace maestro::serve
