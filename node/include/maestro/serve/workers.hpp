#pragma once

#include <functional>
#include <thread>
#include <vector>

#include "maestro/serve/node_core.hpp"

namespace maestro::serve {

/// Threads that take jobs from a node's queue, each running them through
/// `NodeCore::run` with its own scratch, and hand each answer to `Send`.
class Workers {
public:
  /// Called from a worker thread with a job and its answer, which the caller
  /// passes on to `NodeCore::deliver` on the node's event thread.
  using Send = std::function<void(const Job &Finished, Answer)>;

  /// Starts `Count` threads.
  Workers(NodeCore &Core, unsigned Count, Send Reply);

  Workers(const Workers &) = delete;
  Workers &operator=(const Workers &) = delete;

  /// Calls `stop`.
  ~Workers();

  /// Closes the queue and waits for every thread to finish its job.
  void stop();

private:
  void run();

  NodeCore &Core;
  Send Reply;
  std::vector<std::thread> Threads;
};

} // namespace maestro::serve
