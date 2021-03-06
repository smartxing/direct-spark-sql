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

package org.apache.spark.examples.sql.direct

import java.util.concurrent.{CountDownLatch, Executors}

import org.apache.hadoop.hive.ql.exec.UDF
import org.junit.{After, Assert, Before, Test}

import org.apache.spark.examples.sql.TestBase

class DirectExecSuite extends TestBase {

  @Before
  def before(): Unit = {
    spark
      .createDataFrame(Seq(("a", 2, 0), ("bbb", 2, 1), ("c", 3, 0), ("ddd", 4, 1), ("e", 5, 1)))
      .toDF("name", "age", "genda")
      .createOrReplaceTempView("people")
    spark
      .createDataFrame(List(("a", 1, 0), ("b", 2, 1), ("c", 3, 0)))
      .toDF("name", "age", "genda")
      .createOrReplaceTempView("people2")
  }

  @After
  def after(): Unit = {
    spark.close()
  }

  @Test
  def testGenerate(): Unit = {
    assertEquals(
      """
        |select
        |age, str
        |from people
        |LATERAL VIEW
        |explode(split(name, '')) mm
        |as str
        |""".stripMargin,
      true)
  }

  @Test
  def testAgg(): Unit = {
    assertEquals(
      """
        |select
        |genda, count(1)
        |from
        |people group by genda
        |""".stripMargin,
      true)
  }

  @Test
  def testAgg2(): Unit = {
    assertEquals(
      """
        |select
        |genda, approx_count_distinct(age)
        |from
        |people group by genda
        |""".stripMargin,
      true)
  }

  @Test
  def testJoin(): Unit = {
    assertEquals("""
        |select
        |* from people t1
        |join people2 t2
        |on t1.name = t2.name
        |""".stripMargin)
  }

  @Test
  def testWindow(): Unit = {
    assertEquals("""
        |SELECT
        |name,ROW_NUMBER() OVER (PARTITION BY genda ORDER BY name) as row
        |FROM people
        |""".stripMargin)
  }

  @Test
  def testUnion(): Unit = {
    assertEquals("""
        |select * from people
        |union
        |select * from people2
        |""".stripMargin)
  }

  @Test
  def testLeftJoin(): Unit = {
    assertEquals("""
                   |select
                   |* from people t1
                   |left outer join people2 t2
                   |on t1.name = t2.name
                   |""".stripMargin)
  }

  @Test
  def testRightJoin(): Unit = {
    assertEquals("""
                   |select
                   |* from people t1
                   |right outer join people2 t2
                   |on t1.name = t2.name
                   |""".stripMargin)
  }

  @Test
  def testLeftSemiJoin(): Unit = {
    assertEquals("""
                   |select
                   |* from people t1
                   |left semi join people2 t2
                   |on t1.name = t2.name
                   |""".stripMargin)
  }

  @Test
  def testLeftAntiJoin(): Unit = {
    assertEquals("""
                   |select
                   |* from people t1
                   |left anti join people2 t2
                   |on t1.name = t2.name
                   |""".stripMargin)
  }

  @Test
  def testOneRow(): Unit = {
    assertEquals("""
        |select 1 as m, 'a' as n
        |""".stripMargin)
  }

  @Test
  def testHiveUdf(): Unit = {
    spark.sql(s"CREATE TEMPORARY FUNCTION hive_strlen AS '${classOf[StrLen].getName}'")
    assertEquals(
      """
        |select hive_strlen(name), hive_strlen(age)
        |from people
        |""".stripMargin)
  }

  @Test
  def testHiveUdf2(): Unit = {
    spark.sql(s"CREATE FUNCTION hive_strlen2 AS '${classOf[StrLen].getName}'")
    val session = spark.newSession()
    val table = session.sqlDirectly(
      """
        |select hive_strlen2('hyf_test'), hive_strlen2(100)
        |""".stripMargin)
    Assert.assertEquals("[8,200]", table.data.mkString(","))
  }

  @Test
  def testExpand(): Unit = {
    assertEquals(
      """
        |SELECT name, genda, avg(age)
        |FROM people
        |GROUP BY name, genda
        |GROUPING SETS ((name), (genda))
        |""".stripMargin, true)
  }

  @Test
  def testTempView(): Unit = {
    val table = spark.sqlDirectly(
      """
        |select
        |genda, count(1) as cnt
        |from people
        |group by genda
        |order by genda
        |""".stripMargin)
    spark.registerTempView("test", table)
    Assert.assertEquals("[0,2],[1,3]", spark.tempView("test").data.mkString(","))
  }

  @Test
  def testLimit(): Unit = {
    assertEquals(
      """
        |select
        |*
        |from people
        |limit 2
        |""".stripMargin)

    assertEquals(
      """
        |select
        |*
        |from people t1
        |join people2 t2
        |on
        |t1.name = t2.name
        |limit 1
        |""".stripMargin)
  }

  @Test
  def testMultiThread(): Unit = {
    val sql =
      """
        |select age, count(1) as cnt from
        |(
        |select
        |t1.name, t1.age
        |from people t1
        |join people2 t2
        |on
        |t1.age = t2.age
        |) group by age
        |""".stripMargin
    val exp = spark.sql(sql).collect().map(_.mkString(",")).mkString("\n")
    val service = Executors.newFixedThreadPool(10)
    val latch = new CountDownLatch(10)
    (0 until 10).foreach(_ => {
      service.submit(new Runnable {
        override def run(): Unit = {
          val endTime = System.currentTimeMillis() + 30000
          while (System.currentTimeMillis() < endTime) {
            Assert.assertEquals(exp,
              spark.sqlDirectly(sql).data.map(_.mkString(",")).mkString("\n"))
          }
          latch.countDown()
        }
      })
    })
    latch.await()
  }

  @Test
  def testMultiThread2(): Unit = {
    val sql =
      """
        |select age, count(1) as cnt from
        |(
        |select
        |t1.name, t1.age
        |from people t1
        |join people2 t2
        |on
        |t1.age = t2.age
        |) group by age
        |""".stripMargin
    val exp = spark.sql(sql).collect().map(_.mkString(",")).mkString("\n")
    val service = Executors.newFixedThreadPool(10)
    val latch = new CountDownLatch(10)
    (0 until 10).foreach(_ => {
      service.submit(new Runnable {
        override def run(): Unit = {
          val session = spark.newSession()
          session
            .createDataFrame(
              Seq(("a", 2, 0), ("bbb", 2, 1), ("c", 3, 0), ("ddd", 4, 1), ("e", 5, 1)))
            .toDF("name", "age", "genda")
            .createOrReplaceTempView("people")
          session
            .createDataFrame(Seq(("a", 1, 0), ("b", 2, 1), ("c", 3, 0)))
            .toDF("name", "age", "genda")
            .createOrReplaceTempView("people2")
          val endTime = System.currentTimeMillis() + 30000
          while (System.currentTimeMillis() < endTime) {
            Assert.assertEquals(exp,
              session.sqlDirectly(sql).data.map(_.mkString(",")).mkString("\n"))
          }
          latch.countDown()
        }
      })
    })
    latch.await()
  }

  @Test
  def testTakeOrderedAndProjectExec(): Unit = {
    assertEquals(
      """
        |select
        |*
        |from people
        |order by age
        |limit 2
        |""".stripMargin, true)

    assertEquals(
      """
        |select
        |name
        |from people
        |order by age
        |limit 2
        |""".stripMargin, true)
  }

  @Test
  def testTime(): Unit = {
    val first = spark.sqlDirectly(
      """
        |select current_date as m, current_date as n, current_timestamp as tm, current_timestamp as tm1
        |""".stripMargin).data.mkString(",")
    Thread.sleep(1000L)
    val second = spark.sqlDirectly(
      """
        |select current_date as m, current_date as n, current_timestamp as tm, current_timestamp as tm1
        |""".stripMargin).data.mkString(",")
    Assert.assertNotEquals(first, second)
  }

  @Test
  def testTime2(): Unit = {
    val service = Executors.newFixedThreadPool(10)
    val latch = new CountDownLatch(10)
    spark
      .createDataFrame(List(("2019-08-01", 1, 0), ("2017-04-07", 2, 1), ("1992-02-06", 3, 0)))
      .toDF("tm", "age", "genda")
      .createOrReplaceTempView("tb")

    val sql =
      """
        |select
        |unix_timestamp(tm, 'yyyy-MM-dd') as ut,
        |to_unix_timestamp(tm, 'yyyy-MM-dd') as tut,
        |from_unixtime(age, 'yyyy-MM-dd HH:mm:ss') as fut
        |from tb
        |""".stripMargin
    val exp = spark.sql(sql).collect().map(_.mkString(",")).mkString("\n")

    (0 until 10).foreach(_ =>
      service.submit(new Runnable {
        override def run(): Unit = {
          val session = spark.newSession()
          session
            .createDataFrame(List(("2019-08-01", 1, 0), ("2017-04-07", 2, 1), ("1992-02-06", 3, 0)))
            .toDF("tm", "age", "genda")
            .createOrReplaceTempView("tb")
          val endTime = System.currentTimeMillis() + 20000
          while (System.currentTimeMillis() < endTime) {
            Assert.assertEquals(exp,
              session.sqlDirectly(sql).data.map(_.mkString(",")).mkString("\n"))
          }
          latch.countDown()
        }
      }
      )
    )
    latch.await()
  }

}
class StrLen extends UDF {

  def evaluate(input: String): Int =
    input.length()

  def evaluate(input: Int): Int =
    input + 100

}
