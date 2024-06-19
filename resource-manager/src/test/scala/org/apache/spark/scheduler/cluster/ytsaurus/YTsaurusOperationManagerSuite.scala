
package org.apache.spark.scheduler.cluster.ytsaurus

import org.apache.spark.deploy.ytsaurus.Config.{SPARK_PRIMARY_RESOURCE, YTSAURUS_EXTRA_PORTO_LAYER_PATHS, YTSAURUS_PORTO_LAYER_PATHS}
import org.apache.spark.internal.config.{FILES, JARS, SUBMIT_PYTHON_FILES}
import org.apache.spark.{SparkConf, SparkFunSuite}
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import tech.ytsaurus.spyt.test.LocalYt
import tech.ytsaurus.ysontree.YTree

import scala.collection.JavaConverters._
import java.util.stream.Collectors

class YTsaurusOperationManagerSuite extends SparkFunSuite with BeforeAndAfter with Matchers {

  test("Generate application files for python spark-submit in cluster mode") {
    val conf = new SparkConf()
    conf.set(SUBMIT_PYTHON_FILES, Seq("yt:/path/to/my/super/lib.zip"))
    conf.set(SPARK_PRIMARY_RESOURCE, "yt:///path/to/my/super/app.py")

    val result = YTsaurusOperationManager.applicationFiles(conf)

    result should contain theSameElementsAs Seq("//path/to/my/super/app.py", "//path/to/my/super/lib.zip")
  }

  test("Generate application files for python spark-submit in client mode") {
    val conf = new SparkConf()
    conf.set(FILES, Seq("yt:/path/to/my/super/lib.zip"))
    conf.set(SUBMIT_PYTHON_FILES, Seq("/tmp/spark-164a106b-cc57-4bb6-b30f-e67b7bbb8d8a/lib.zip"))
    conf.set(SPARK_PRIMARY_RESOURCE, "yt:///path/to/my/super/app.py")

    val result = YTsaurusOperationManager.applicationFiles(conf)

    result should contain theSameElementsAs Seq("//path/to/my/super/app.py", "//path/to/my/super/lib.zip")
  }

  test("Generate application files for java spark-submit") {
    val conf = new SparkConf()
    conf.set(JARS, Seq("yt:/path/to/my/super/lib.jar", "yt:///path/to/my/super/app.jar"))
    conf.set(SPARK_PRIMARY_RESOURCE, "yt:///path/to/my/super/app.jar")

    val result = YTsaurusOperationManager.applicationFiles(conf)

    result should contain theSameElementsAs Seq("//path/to/my/super/lib.jar", "//path/to/my/super/app.jar")
  }

  test("Generate application files for spark-shell") {
    val conf = new SparkConf()
    conf.set(SPARK_PRIMARY_RESOURCE, "spark-shell")

    val result = YTsaurusOperationManager.applicationFiles(conf)

    result shouldBe empty
  }

  test("Test layer_paths override") {
    val conf = new SparkConf()
    conf.set(SPARK_PRIMARY_RESOURCE, "spark-shell")
    conf.set(YTSAURUS_PORTO_LAYER_PATHS, "//path/to/layers/1,//path/to/layers/2")

    val result = YTsaurusOperationManager.getPortoLayers(conf, YTree.listBuilder().buildList()).asList().asScala.map(x => x.stringValue())

    result should contain theSameElementsAs Seq("//path/to/layers/1", "//path/to/layers/2")
  }

  test("Test layer_paths override + extra layers") {
    val conf = new SparkConf()
    conf.set(SPARK_PRIMARY_RESOURCE, "spark-shell")
    conf.set(YTSAURUS_PORTO_LAYER_PATHS, "//path/to/layers/1,//path/to/layers/2")
    conf.set(YTSAURUS_EXTRA_PORTO_LAYER_PATHS, "//path/to/layers/3,//path/to/layers/4")

    val result = YTsaurusOperationManager.getPortoLayers(conf, YTree.listBuilder().buildList()).asList().asScala.map(x => x.stringValue())

    result should contain theSameElementsAs Seq(
      "//path/to/layers/1",
      "//path/to/layers/2",
      "//path/to/layers/3",
      "//path/to/layers/4",
    )
  }

}
