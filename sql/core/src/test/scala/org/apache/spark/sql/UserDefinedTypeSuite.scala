/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql

import java.io.File

import org.apache.spark.util.Utils

import scala.beans.{BeanInfo, BeanProperty}

import com.clearspring.analytics.stream.cardinality.HyperLogLog

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.expressions.{OpenHashSetUDT, HyperLogLogUDT}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.test.TestSQLContext
import org.apache.spark.sql.test.TestSQLContext.{sparkContext, sql}
import org.apache.spark.sql.test.TestSQLContext.implicits._
import org.apache.spark.sql.types._
import org.apache.spark.util.collection.OpenHashSet

@SQLUserDefinedType(udt = classOf[MyDenseVectorUDT])
private[sql] class MyDenseVector(val data: Array[Double]) extends Serializable {
  override def equals(other: Any): Boolean = other match {
    case v: MyDenseVector =>
      java.util.Arrays.equals(this.data, v.data)
    case _ => false
  }

  override def toString =
    '[' + data.mkString(",") + ']'
}

@BeanInfo
private[sql] case class MyLabeledPoint(
    @BeanProperty label: Double,
    @BeanProperty features: MyDenseVector)

private[sql] class MyDenseVectorUDT extends UserDefinedType[MyDenseVector] {

  override def sqlType: DataType = ArrayType(DoubleType, containsNull = false)

  override def serialize(obj: Any): Seq[Double] = {
    obj match {
      case features: MyDenseVector =>
        features.data.toSeq
    }
  }

  override def deserialize(datum: Any): MyDenseVector = {
    datum match {
      case data: Seq[_] =>
        new MyDenseVector(data.asInstanceOf[Seq[Double]].toArray)
      case data: MyDenseVector =>
        data
    }
  }

  override def userClass: Class[MyDenseVector] = classOf[MyDenseVector]

  private[spark] override def asNullable: MyDenseVectorUDT = this
}

class UserDefinedTypeSuite extends QueryTest {
  val points = Seq(
    MyLabeledPoint(1.0, new MyDenseVector(Array(0.1, 1.0))),
    MyLabeledPoint(0.0, new MyDenseVector(Array(0.2, 2.0))))
  val pointsRDD = sparkContext.parallelize(points).toDF()


  test("register user type: MyDenseVector for MyLabeledPoint") {
    val labels: RDD[Double] = pointsRDD.select('label).rdd.map { case Row(v: Double) => v }
    val labelsArrays: Array[Double] = labels.collect()
    assert(labelsArrays.size === 2)
    assert(labelsArrays.contains(1.0))
    assert(labelsArrays.contains(0.0))

    val features: RDD[MyDenseVector] =
      pointsRDD.select('features).rdd.map { case Row(v: MyDenseVector) => v }
    val featuresArrays: Array[MyDenseVector] = features.collect()
    assert(featuresArrays.size === 2)
    assert(featuresArrays.contains(new MyDenseVector(Array(0.1, 1.0))))
    assert(featuresArrays.contains(new MyDenseVector(Array(0.2, 2.0))))
  }

  test("UDTs and UDFs") {
    TestSQLContext.udf.register("testType", (d: MyDenseVector) => d.isInstanceOf[MyDenseVector])
    pointsRDD.registerTempTable("points")
    checkAnswer(
      sql("SELECT testType(features) from points"),
      Seq(Row(true), Row(true)))
  }


  test("UDTs with Parquet") {
    val tempDir = Utils.createTempDir()
    tempDir.delete()
    pointsRDD.saveAsParquetFile(tempDir.getCanonicalPath)
  }

  test("Repartition UDTs with Parquet") {
    val tempDir = Utils.createTempDir()
    tempDir.delete()
    pointsRDD.repartition(1).saveAsParquetFile(tempDir.getCanonicalPath)
  }

  // Tests to make sure that all operators correctly convert types on the way out.
  test("Local UDTs") {
    val df = Seq((1, new MyDenseVector(Array(0.1, 1.0)))).toDF("int", "vec")
    df.collect()(0).getAs[MyDenseVector](1)
    df.take(1)(0).getAs[MyDenseVector](1)
    df.limit(1).groupBy('int).agg(first('vec)).collect()(0).getAs[MyDenseVector](0)
    df.orderBy('int).limit(1).groupBy('int).agg(first('vec)).collect()(0).getAs[MyDenseVector](0)
  }

  test("HyperLogLogUDT") {
    val hyperLogLogUDT = HyperLogLogUDT
    val hyperLogLog = new HyperLogLog(0.4)
    (1 to 10).foreach(i => hyperLogLog.offer(Row(i)))

    val actual = hyperLogLogUDT.deserialize(hyperLogLogUDT.serialize(hyperLogLog))
    assert(actual.cardinality() === hyperLogLog.cardinality())
    assert(java.util.Arrays.equals(actual.getBytes, hyperLogLog.getBytes))
  }

  test("OpenHashSetUDT") {
    val openHashSetUDT = new OpenHashSetUDT(IntegerType)
    val set = new OpenHashSet[Int]
    (1 to 10).foreach(i => set.add(i))

    val actual = openHashSetUDT.deserialize(openHashSetUDT.serialize(set))
    assert(actual.iterator.toSet === set.iterator.toSet)
  }

  test("UDTs with JSON") {
    val data = Seq(
      "{\"id\":1,\"vec\":[1.1,2.2,3.3,4.4]}",
      "{\"id\":2,\"vec\":[2.25,4.5,8.75]}"
    )
    val schema = StructType(Seq(
      StructField("id", IntegerType, false),
      StructField("vec", new MyDenseVectorUDT, false)
    ))

    val stringRDD = sparkContext.parallelize(data)
    val jsonRDD = new SQLContext(sparkContext).jsonRDD(stringRDD, schema)
    assertResult("[1,[1.1,2.2,3.3,4.4]],[2,[2.25,4.5,8.75]]") {
      jsonRDD.collect().mkString(",")
    }
  }
}
