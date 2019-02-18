package com.Lucas

import com.Lucas.GetDatabaseData.wordVec4Tags
import com.Lucas.config._

import org.apache.spark.{SparkContext, SparkConf}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.rdd.RDD

object Tags2WordVector {
  val logger: Logger = Logger.getLogger(Tags2WordVector.getClass)

  def main(args: Array[String]): Unit = {
    val sparkConf: SparkConf = new SparkConf().setAppName("Tags2WordVector")
    var runType = "cluster"
    if (args.length == 0){
      runType = "local"
      sparkConf.setMaster("local")
    }
    val sc: SparkContext = new SparkContext(sparkConf)
    val contentInfo: RDD[String] = sc.textFile(saveContentInfoHdfs, 20)
    /*
    val wordVectorInfo: RDD[String] = wordVec4Tags(contentInfo, runType).cache()
    logger.warn("wordVectorInfo first: " + wordVectorInfo.first().toString)
    try{
      wordVectorInfo.coalesce(20).saveAsTextFile(saveContentWordVecHdfs)
    }catch {
      case _: Throwable => logger.error("fuck error")
    }
    */

    // 根据content ID对查询得到的资讯数据去重
//    contentInfo.map(line => (line.split(",")(0), line))
//      .reduceByKey((key, line) => line)
//      .map(line => line._2).coalesce(20).saveAsTextFile(saveContentInfoHdfs + "2")
    contentInfo.map(line => line.split("(")(1))
      .map(line => line.split(")")(0))
      .coalesce(20).saveAsTextFile(saveContentInfoHdfs + "1")

  }

}
