/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical.idp

import org.neo4j.cypher.internal.compiler.planner.logical.LogicalPlanningContext
import org.neo4j.cypher.internal.compiler.planner.logical.QueryPlannerKit
import org.neo4j.cypher.internal.compiler.planner.logical.idp.IDPQueryGraphSolver.extraRequirementForInterestingOrder
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.ir.ordering.InterestingOrder
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.time.Stopwatch

/**
 * This class is responsible for connecting all disconnected logical plans, which can be
 * done with hash joins when an useful predicate connects the two plans, or with cartesian
 * product lacking that.
 *
 * The input is a set of disconnected patterns and this class will find the
 * cheapest way to connect all components using IDP.
 */
case class ComponentConnectorPlanner(singleComponentPlanner: SingleComponentPlannerTrait, config: IDPSolverConfig, monitor: IDPSolverMonitor)
  extends JoinDisconnectedQueryGraphComponents {

  private val connectors = Seq(
    CartesianProductComponentConnector,
    NestedIndexJoinComponentConnector(singleComponentPlanner),
    ValueHashJoinComponentConnector,
  )

  // Ideas:
  // - If NIJ connector and VHJ connector == empty step solver && components > 8 then fallback to leftdeep CP tree.
  // - Conditional connectors: Only ask CPCC if no hash join found for goal.
  // - If all individual components together solve all predicates we can:
  //   - Skip selecting generators altogether
  //   - If also no joins are possible, a left-deep CP tree sorted by cost will be the best plan. No need for IDP.
  def apply(components: Set[PlannedComponent],
            queryGraph: QueryGraph,
            interestingOrder: InterestingOrder,
            context: LogicalPlanningContext,
            kit: QueryPlannerKit,
            singleComponentPlanner: SingleComponentPlannerTrait): Set[PlannedComponent] = {
    require(components.size > 1, "Can't connect less than 2 components.")

    val orderRequirement = extraRequirementForInterestingOrder(context, interestingOrder)

    val generators = connectors.map(_.solverStep(queryGraph, interestingOrder, kit))
    val generator = IDPQueryGraphSolver.composeGenerators(queryGraph, interestingOrder, kit, context, generators)

    val solver = new IDPSolver[QueryGraph, LogicalPlan, LogicalPlanningContext](
      generator = generator,
      projectingSelector = kit.pickBest,
      maxTableSize = config.maxTableSize,
      iterationDurationLimit = config.iterationDurationLimit,
      extraRequirement = orderRequirement,
      monitor = monitor,
      stopWatchFactory = Stopwatch.start
    )

    val seed: Seed[QueryGraph, LogicalPlan] = components.flatMap {
      case PlannedComponent(queryGraph, plan) => Set(
        ((Set(queryGraph), false), plan.bestResult)
      ) ++ plan.bestResultFulfillingReq.map { bestSortedResult =>
        ((Set(queryGraph), true), bestSortedResult)
      }
    }
    val initialTodo = components.map(_.queryGraph)

    val bestPlans = solver(seed, initialTodo, context)

    Set(PlannedComponent(queryGraph, bestPlans))
  }
}

trait ComponentConnector {
  def solverStep(queryGraph: QueryGraph, interestingOrder: InterestingOrder, kit: QueryPlannerKit): ComponentConnectorSolverStep
}
