/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.plan.rules.logical

import org.apache.calcite.plan.RelOptRuleCall
import org.apache.calcite.rel.core.Filter
import org.apache.calcite.sql.fun.SqlStdOperatorTable._

/**
  * Merge multi OR to a IN.
  * e.g:
  * input predicate: (a = 10 OR a = 20 OR a = 21) AND b = 5
  * output expressions: a IN (10, 20, 21) AND b = 5.
  */
class MergeMultiEqualsToInRule
  extends MergeToNotInOrInRule("MergeMultiEqualsToInRule") {

  override def onMatch(call: RelOptRuleCall): Unit = {
    val filter: Filter = call.rel(0)

    convertToNotInOrIn(call.builder(), filter.getCondition, EQUALS, OR, AND, IN) match {
      case Some(newRex) =>
        call.transformTo(filter.copy(
          filter.getTraitSet,
          filter.getInput,
          newRex))
      case None =>
    }
  }
}

object MergeMultiEqualsToInRule {
  val INSTANCE = new MergeMultiEqualsToInRule
}
