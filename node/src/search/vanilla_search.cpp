#include "maestro/search/vanilla_search.hpp"

#include <algorithm>
#include <cassert>
#include <limits>

namespace maestro::search {

void VanillaSearch::find(const world::Area &Area, const Profile &Mob,
                         const Request &Call, Result &Out) {
  OpenSet.clear();
  Evaluator.prepare(Area, Mob, Call);
  const NodeId From = Evaluator.getStart();
  const NodeId TargetId = Evaluator.getTarget(Call.Target);
  const Node &TargetNode = Evaluator.node(TargetId);
  Goal = Node{.X = TargetNode.X, .Y = TargetNode.Y, .Z = TargetNode.Z};
  BestHeuristic = std::numeric_limits<float>::max();
  BestNode = NoNode;

  Node &Start = Evaluator.node(From);
  Start.G = 0.0F;
  Start.H = getBestH(From);
  Start.F = Start.H;
  OpenSet.insert(From);

  int Count = 0;
  bool Reached = false;
  // Vanilla scales the budget by a multiplier, which is 1 for every land mob.
  const int MaxVisitedNodes = Call.MaxVisitedNodes;
  while (!OpenSet.empty()) {
    if (++Count >= MaxVisitedNodes)
      break;

    const NodeId Current = OpenSet.pop();
    Evaluator.node(Current).Closed = true;
    if (distanceManhattan(Evaluator.node(Current), Goal) <=
        static_cast<float>(Call.ReachRange))
      Reached = true;
    if (Reached)
      break;

    if (distanceTo(Evaluator.node(Current), Evaluator.node(From)) >=
        Call.MaxPathLength)
      continue;

    // Finding neighbours can grow the node table, so only take node
    // references after it returns.
    const int NeighborCount = Evaluator.getNeighbors(Neighbors, Current);
    for (int I = 0; I < NeighborCount; ++I) {
      const NodeId Next = Neighbors[I];
      const Node &Cur = Evaluator.node(Current);
      Node &Neighbor = Evaluator.node(Next);
      const float Distance = distanceTo(Cur, Neighbor);
      Neighbor.WalkedDistance = Cur.WalkedDistance + Distance;
      const float TentativeGScore = Cur.G + Distance + Neighbor.CostMalus;
      if (!(Neighbor.WalkedDistance < Call.MaxPathLength) ||
          (Neighbor.inOpenSet() && !(TentativeGScore < Neighbor.G)))
        continue;
      Neighbor.CameFrom = Current;
      Neighbor.G = TentativeGScore;
      Neighbor.H = getBestH(Next) * 1.5F;
      if (Neighbor.inOpenSet()) {
        OpenSet.changeCost(Next, Neighbor.G + Neighbor.H);
      } else {
        Neighbor.F = Neighbor.G + Neighbor.H;
        OpenSet.insert(Next);
      }
    }
  }

  assert(BestNode != NoNode && "the start node is always a candidate");
  Out.Nodes.clear();
  for (NodeId Id = BestNode; Id != NoNode; Id = Evaluator.node(Id).CameFrom) {
    const Node &Step = Evaluator.node(Id);
    Out.Nodes.push_back(PathPoint{{Step.X, Step.Y, Step.Z}, Step.Type});
  }
  std::reverse(Out.Nodes.begin(), Out.Nodes.end());
  Out.Reached = Reached;
  Out.Cost = Evaluator.node(BestNode).G;
  Evaluator.done();
}

float VanillaSearch::getBestH(NodeId Id) {
  const float H = distanceTo(Evaluator.node(Id), Goal);
  if (H < BestHeuristic) {
    BestHeuristic = H;
    BestNode = Id;
  }
  return std::min(H, std::numeric_limits<float>::max());
}

} // namespace maestro::search
