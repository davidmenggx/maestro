#include "maestro/serve/request_queue.hpp"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <optional>
#include <thread>
#include <vector>

#include <gtest/gtest.h>

using namespace maestro::serve;

namespace {

constexpr Clock::duration Retention = std::chrono::milliseconds(500);
const Clock::time_point Start{};

Job job(std::uint64_t RequestId) {
  Job Next;
  Next.RequestId = RequestId;
  Next.Mob = std::make_shared<const maestro::search::Profile>();
  return Next;
}

Answer answer(std::uint8_t Byte) {
  return std::make_shared<const std::vector<std::uint8_t>>(1, Byte);
}

Admission admit(RequestQueue &Queue, std::uint64_t RequestId,
                Clock::time_point Now = Start) {
  Answer Ignored;
  return Queue.admit(job(RequestId), Now, Ignored);
}

/// Takes the next job, which must be `RequestId`, and answers it.
void serve(RequestQueue &Queue, std::uint64_t RequestId, Answer Result,
           Clock::time_point Now = Start) {
  const std::optional<Job> Next = Queue.take();
  ASSERT_TRUE(Next);
  ASSERT_EQ(Next->RequestId, RequestId);
  EXPECT_TRUE(Queue.settle(*Next, std::move(Result), Now));
}

} // namespace

TEST(RequestQueue, ServesInArrivalOrderAndCountsRunningRequests) {
  RequestQueue Queue(Retention);
  for (std::uint64_t Id = 1; Id <= 3; ++Id)
    EXPECT_EQ(admit(Queue, Id), Admission::Queued);
  EXPECT_EQ(Queue.depth(), 3u);

  const std::optional<Job> First = Queue.take();
  ASSERT_TRUE(First);
  EXPECT_EQ(First->RequestId, 1u);
  // Running, so still held.
  EXPECT_EQ(Queue.depth(), 3u);
  EXPECT_TRUE(Queue.settle(*First, answer(1), Start));
  EXPECT_EQ(Queue.depth(), 2u);
  serve(Queue, 2, answer(2));
  serve(Queue, 3, answer(3));
  EXPECT_EQ(Queue.depth(), 0u);
}

TEST(RequestQueue, ACopyOfAnOpenRequestIsDropped) {
  RequestQueue Queue(Retention);
  ASSERT_EQ(admit(Queue, 7), Admission::Queued);
  EXPECT_EQ(admit(Queue, 7), Admission::Duplicate);
  EXPECT_EQ(Queue.depth(), 1u);

  const std::optional<Job> Running = Queue.take();
  ASSERT_TRUE(Running);
  EXPECT_EQ(admit(Queue, 7), Admission::Duplicate);
  EXPECT_EQ(Queue.depth(), 1u);
}

TEST(RequestQueue, ACopyOfAnAnsweredRequestGetsTheSameAnswer) {
  RequestQueue Queue(Retention);
  ASSERT_EQ(admit(Queue, 7), Admission::Queued);
  const Answer First = answer(42);
  serve(Queue, 7, First);

  Answer Cached;
  EXPECT_EQ(Queue.admit(job(7), Start + Retention / 2, Cached),
            Admission::Answered);
  EXPECT_EQ(Cached, First);
  EXPECT_EQ(Queue.depth(), 0u);
}

TEST(RequestQueue, AnAnswerIsKeptOnlyForTheRetention) {
  RequestQueue Queue(Retention);
  ASSERT_EQ(admit(Queue, 7), Admission::Queued);
  serve(Queue, 7, answer(1));

  const Clock::time_point Later = Start + Retention;
  EXPECT_EQ(admit(Queue, 7, Later), Admission::Queued);
  serve(Queue, 7, answer(2), Later);
  // The newer answer has its own retention.
  Answer Cached;
  EXPECT_EQ(Queue.admit(job(7), Later + Retention / 2, Cached),
            Admission::Answered);
  EXPECT_EQ(*Cached, std::vector<std::uint8_t>{2});
}

TEST(RequestQueue, ACancelDropsAWaitingRequest) {
  RequestQueue Queue(Retention);
  ASSERT_EQ(admit(Queue, 1), Admission::Queued);
  ASSERT_EQ(admit(Queue, 2), Admission::Queued);

  EXPECT_TRUE(Queue.cancel(1, Start));
  EXPECT_EQ(Queue.depth(), 1u);
  serve(Queue, 2, answer(2));
  // A later copy is dropped too.
  EXPECT_EQ(admit(Queue, 1), Admission::Canceled);
  EXPECT_EQ(Queue.depth(), 0u);
}

TEST(RequestQueue, ACancelLeavesARunningOrAnsweredRequestAlone) {
  RequestQueue Queue(Retention);
  ASSERT_EQ(admit(Queue, 1), Admission::Queued);
  const std::optional<Job> Running = Queue.take();
  ASSERT_TRUE(Running);

  EXPECT_FALSE(Queue.cancel(1, Start));
  EXPECT_EQ(Queue.depth(), 1u);
  const Answer Result = answer(1);
  EXPECT_TRUE(Queue.settle(*Running, Result, Start));
  EXPECT_FALSE(Queue.cancel(1, Start));

  Answer Cached;
  EXPECT_EQ(Queue.admit(job(1), Start, Cached), Admission::Answered);
  EXPECT_EQ(Cached, Result);
}

TEST(RequestQueue, ACancelThatArrivesFirstDropsTheRequestUntilItExpires) {
  RequestQueue Queue(Retention);
  EXPECT_FALSE(Queue.cancel(5, Start));
  EXPECT_EQ(admit(Queue, 5, Start + Retention / 2), Admission::Canceled);
  EXPECT_EQ(Queue.depth(), 0u);
  EXPECT_EQ(admit(Queue, 5, Start + Retention), Admission::Queued);
}

TEST(RequestQueue, ARequestQueuedAgainAfterItsCancelExpiredIsServedOnce) {
  RequestQueue Queue(Retention);
  ASSERT_EQ(admit(Queue, 1), Admission::Queued);
  ASSERT_TRUE(Queue.cancel(1, Start));
  // The canceled job is still in the queue, behind nothing.
  ASSERT_EQ(admit(Queue, 1, Start + Retention), Admission::Queued);
  ASSERT_EQ(admit(Queue, 2, Start + Retention), Admission::Queued);
  EXPECT_EQ(Queue.depth(), 2u);

  serve(Queue, 1, answer(1), Start + Retention);
  serve(Queue, 2, answer(2), Start + Retention);
  EXPECT_EQ(Queue.depth(), 0u);
}

TEST(RequestQueue, ClearForgetsEveryRequest) {
  RequestQueue Queue(Retention);
  ASSERT_EQ(admit(Queue, 1), Admission::Queued);
  ASSERT_EQ(admit(Queue, 2), Admission::Queued);
  ASSERT_EQ(admit(Queue, 3), Admission::Queued);
  serve(Queue, 1, answer(1));
  ASSERT_TRUE(Queue.cancel(3, Start));
  const std::optional<Job> Running = Queue.take();
  ASSERT_TRUE(Running);
  ASSERT_EQ(Running->RequestId, 2u);

  Queue.clear();
  // The running job still holds a worker.
  EXPECT_EQ(Queue.depth(), 1u);
  // It belongs to the old link, so its answer is not kept or sent.
  EXPECT_FALSE(Queue.settle(*Running, answer(2), Start));
  EXPECT_EQ(Queue.depth(), 0u);
  // Ids mean nothing across links: none is answered, canceled or open.
  EXPECT_EQ(admit(Queue, 1), Admission::Queued);
  EXPECT_EQ(admit(Queue, 2), Admission::Queued);
  EXPECT_EQ(admit(Queue, 3), Admission::Queued);
  EXPECT_EQ(Queue.depth(), 3u);
}

TEST(RequestQueue, CloseWakesAWaitingWorker) {
  RequestQueue Queue(Retention);
  std::optional<Job> Taken = job(99);
  std::thread Worker([&] { Taken = Queue.take(); });
  Queue.close();
  Worker.join();
  EXPECT_FALSE(Taken);
  // Closed for good, even with work waiting.
  ASSERT_EQ(admit(Queue, 1), Admission::Queued);
  EXPECT_FALSE(Queue.take());
}

TEST(RequestQueue, WorkersServeEachRequestOnceWhileItIsCanceledAndCopied) {
  // Under ThreadSanitizer this also checks for data races.
  constexpr std::uint64_t Requests = 4000;
  RequestQueue Queue(Retention);
  std::atomic<int> Served[Requests] = {};
  std::vector<std::thread> Workers;
  for (int I = 0; I < 4; ++I)
    Workers.emplace_back([&] {
      while (const std::optional<Job> Next = Queue.take()) {
        ++Served[Next->RequestId];
        Queue.settle(*Next, answer(1), Clock::now());
      }
    });

  for (std::uint64_t Id = 0; Id < Requests; ++Id) {
    Answer Cached;
    Queue.admit(job(Id), Clock::now(), Cached);
    Queue.admit(job(Id), Clock::now(), Cached);
    if (Id % 3 == 0)
      Queue.cancel(Id, Clock::now());
  }
  while (Queue.depth() > 0)
    std::this_thread::yield();
  Queue.close();
  for (std::thread &Worker : Workers)
    Worker.join();

  for (std::uint64_t Id = 0; Id < Requests; ++Id)
    ASSERT_LE(Served[Id].load(), 1) << Id;
}
