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

package org.apache.flink.table.tpc

import org.apache.flink.table.tpc.STATS_MODE.STATS_MODE

import org.apache.calcite.sql.SqlExplainLevel
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

import java.util

import scala.collection.JavaConversions._

@RunWith(classOf[Parameterized])
class TpcH10TBatchExecPlanTest(
    caseName: String,
    factor: Int,
    statsMode: STATS_MODE,
    explainLevel: SqlExplainLevel,
    joinReorderEnabled: Boolean,
    printOptimizedResult: Boolean)
  extends TpcHBatchExecPlanTest(
    caseName, factor, statsMode, explainLevel, joinReorderEnabled, printOptimizedResult)

object TpcH10TBatchExecPlanTest {
  @Parameterized.Parameters(name = "caseName={0}, factor={1}, statsMode={2}, joinReorder={4}")
  def parameters(): util.Collection[Array[Any]] = {
    val factor = 10240
    val explainLevel = SqlExplainLevel.EXPPLAN_ATTRIBUTES
    val joinReorderEnabled = true
    val printResult = false
    util.Arrays.asList(
      "01", "02", "03", "04", "05", "06",
      "07", // runtime_filter should push down
      "08",
      "09", // Rowcount estimated wrong, estimated at 200 million, in fact 3 billion.
      "10", // 12E -> 6KW no localagg? calc no push down?
      "11", "12",
      "13", // group by count need do localAgg.(data skew)
      "14", "15_1", "16",
      "17",
      "18", // totally wrong.. build side wrong.
      "19", "20",
      "21", // order join can reduce half data, why not push down. (join reorder)
      "22"
    ).flatMap { s =>
      Seq(
        Array(s, factor, STATS_MODE.ROW_COUNT, explainLevel, joinReorderEnabled, printResult),
        Array(s, factor, STATS_MODE.PART, explainLevel, joinReorderEnabled, printResult),
        Array(s, factor, STATS_MODE.FULL, explainLevel, joinReorderEnabled, printResult)
      )
    }
  }

}
