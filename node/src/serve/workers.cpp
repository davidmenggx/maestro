#include "maestro/serve/workers.hpp"

#include <cstdint>
#include <memory>
#include <utility>

#include <flatbuffers/flatbuffers.h>

namespace maestro::serve {

Workers::Workers(RequestQueue &Queue, const world::WorldStore &Store,
                 fbs::Pathfinder Kind, unsigned Count, Send Reply)
    : Queue(Queue), Store(Store), Reply(std::move(Reply)) {
  Threads.reserve(Count);
  for (unsigned I = 0; I < Count; ++I)
    Threads.emplace_back([this, Kind] { run(Kind); });
}

Workers::~Workers() { stop(); }

void Workers::stop() {
  Queue.close();
  for (std::thread &Thread : Threads)
    if (Thread.joinable())
      Thread.join();
}

void Workers::run(fbs::Pathfinder Kind) {
  const auto Search = search::makeSearch(Kind);
  flatbuffers::FlatBufferBuilder Builder;
  search::Result Path;
  while (std::optional<Job> Next = Queue.take()) {
    const auto Held = Store.pin();
    std::uint64_t Sequence = 0;
    if (Held) {
      Search->find(*Held, *Next->Mob, Next->Call, Path);
      Sequence = Held->sequence();
    } else {
      Path.Nodes.clear();
      Path.Reached = false;
      Path.Cost = 0.0F;
    }
    Answer Result;
    if (search::encodeResponse(Builder, Next->RequestId, Path, Sequence))
      Result = std::make_shared<const std::vector<std::uint8_t>>(
          Builder.GetBufferPointer(),
          Builder.GetBufferPointer() + Builder.GetSize());
    if (Queue.settle(*Next, Result, Clock::now()) && Result)
      Reply(Next->ReplyTo, std::move(Result));
  }
}

} // namespace maestro::serve
