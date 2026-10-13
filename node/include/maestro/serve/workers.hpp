#pragma once

#include <functional>
#include <thread>
#include <vector>

#include <asio/ip/udp.hpp>

#include "maestro/serve/request_queue.hpp"
#include "maestro/world/world_store.hpp"
#include "pathfinding_generated.h"

namespace maestro::serve {

/// Threads that take jobs from a `RequestQueue`, each running its own search
/// on the version of the area current when the job starts, and hand each
/// answer to `Send`. A node that holds no area answers with no path at
/// sequence 0, which the controller reads as a search that ran too early.
class Workers {
public:
  /// Called from a worker thread.
  using Send =
      std::function<void(const asio::ip::udp::endpoint &ReplyTo, Answer)>;

  /// Starts `Count` threads running the search `Kind`, which this build must
  /// have.
  Workers(RequestQueue &Queue, const world::WorldStore &Store,
          fbs::Pathfinder Kind, unsigned Count, Send Reply);

  Workers(const Workers &) = delete;
  Workers &operator=(const Workers &) = delete;

  /// Calls `stop`.
  ~Workers();

  /// Closes the queue and waits for every thread to finish its job.
  void stop();

private:
  void run(fbs::Pathfinder Kind);

  RequestQueue &Queue;
  const world::WorldStore &Store;
  Send Reply;
  std::vector<std::thread> Threads;
};

} // namespace maestro::serve
