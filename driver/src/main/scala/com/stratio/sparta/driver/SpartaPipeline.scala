/*
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.sparta.driver

import com.stratio.sparta.driver.cube.CubeMaker
import com.stratio.sparta.driver.factory.SparkContextFactory._
import com.stratio.sparta.driver.helper.SchemaHelper
import com.stratio.sparta.driver.service.RawDataStorageService
import com.stratio.sparta.driver.stage._
import com.stratio.sparta.driver.writer.StreamWriter
import com.stratio.sparta.sdk.pipeline.input.Input
import com.stratio.sparta.sdk.utils.AggregationTime
import com.stratio.sparta.serving.core.helpers.PolicyHelper
import com.stratio.sparta.serving.core.models.policy._
import com.stratio.sparta.serving.core.utils.CheckpointUtils
import org.apache.curator.framework.CuratorFramework
import org.apache.spark.sql.Row
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.{Duration, Milliseconds, StreamingContext}

class SpartaPipeline(val policy: PolicyModel, val curatorFramework: CuratorFramework) extends CheckpointUtils
  with InputStage
  with OutputStage
  with ParserStage
  with CubeStage
  with ZooKeeperError {

  private val ReflectionUtils = PolicyHelper.ReflectionUtils

  def run(): StreamingContext = {
    clearError()
    val checkpointPolicyPath = checkpointPath(policy)
    val window = AggregationTime.parseValueToMilliSeconds(policy.sparkStreamingWindow)
    val ssc = sparkStreamingInstance(Duration(window), checkpointPolicyPath, policy.remember)
    val parserSchemas = SchemaHelper.getSchemasFromParsers(policy.transformations, Input.InitSchema)
    val parsers = parserStage(ReflectionUtils, parserSchemas).sorted
    val cubes = cubeStage(ReflectionUtils, parserSchemas.values.last)
    val outputs = outputStage(ReflectionUtils)

    outputs.foreach(output => output.setup())

    val input = inputStage(ssc.get, ReflectionUtils)
    val inputDStream = inputStreamStage(ssc.get, input)

    saveRawData(policy.rawData, inputDStream)

    val parsedData = ParserStage.applyParsers(inputDStream, parsers)

    triggerStage(policy.streamTriggers)
      .groupBy(trigger => (trigger.triggerWriterOptions.overLast, trigger.triggerWriterOptions.computeEvery))
      .foreach { case ((overLast, computeEvery), triggers) =>

        val groupedData = (overLast, computeEvery) match {
          case (None, None) => parsedData
          case (Some(overL), Some(computeE))
            if (AggregationTime.parseValueToMilliSeconds(overL) == window) &&
              (AggregationTime.parseValueToMilliSeconds(computeE) == window) => parsedData
          case _ => parsedData.window(
            Milliseconds(
              overLast.fold(window) { over => AggregationTime.parseValueToMilliSeconds(over) }),
            Milliseconds(
              computeEvery.fold(window) { computeEvery =>AggregationTime.parseValueToMilliSeconds(computeEvery)}))
        }

        StreamWriter(triggers, streamTemporalTable(policy.streamTemporalTable), outputs)
          .write(groupedData, parserSchemas.values.last)
      }

    val dataCube = CubeMaker(cubes).setUp(parsedData)
    dataCube.foreach { case (cubeName, aggregatedData) =>
      getCubeWriter(cubeName, cubes, outputs).write(aggregatedData)
    }
    ssc.get
  }

  def saveRawData(rawModel: RawDataModel, input: DStream[Row]): Unit =
    if (rawModel.enabled.toBoolean) {
      require(!rawModel.path.equals("default"), "The parquet path must be set")
      RawDataStorageService.save(input, rawModel.path)
    }

  def streamTemporalTable(policyTableName: Option[String]): String =
    policyTableName.flatMap(tableName => if (tableName.nonEmpty) Some(tableName) else None)
      .getOrElse("stream")
}

object SpartaPipeline {

  def apply(policy: PolicyModel, curatorFramework: CuratorFramework): SpartaPipeline =
    new SpartaPipeline(policy, curatorFramework)
}
