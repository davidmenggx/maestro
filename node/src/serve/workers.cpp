#include "maestro/serve/workers.hpp"

#include <optional>
#include <utility>

namespace maestro::serve {

Workers::Workers(NodeCore &Core, unsigned Count, Send Reply)
    : Core(Core), Reply(std::move(Reply)) {
  Threads.reserve(Count);
  for (unsigned I = 0; I < Count; ++I)
    Threads.emplace_back([this] { run(); });
}

Workers::~Workers() { stop(); }

void Workers::stop() {
  Core.queue().close();
  for (std::thread &Thread : Threads)
    if (Thread.joinable())
      Thread.join();
}

void Workers::run() {
  Scratch With(Core.searchKind());
  while (const std::optional<Job> Next = Core.queue().take())
    if (Outcome Done = Core.run(*Next, With); Done.Result)
      Reply(*Next, std::move(Done.Result));
}

} // namespace maestro::serve
