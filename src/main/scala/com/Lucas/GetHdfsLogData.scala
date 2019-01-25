package com.Lucas

import com.Lucas.config._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.{SparkConf, SparkContext}
import java.util.Date
import java.text.SimpleDateFormat
import com.alibaba.fastjson.JSONObject
import com.alibaba.fastjson.JSON
import org.apache.spark.rdd.RDD

object GetHdfsLogData {
  val logger: Logger = Logger.getLogger(GetHdfsLogData.getClass)

  def main(args: Array[String]): Unit = {
    val sparkConf: SparkConf = new SparkConf().setAppName("GetHdfsLogData")
    var runType = "spark"
    if (args.length == 0){
      runType = "local"
      sparkConf.setMaster("local")
    }
    val sc: SparkContext = new SparkContext(sparkConf)
    sc.setLogLevel("WARN")
    // 只获取当天的日志数据，展示日志及每条日志是否点击
    // 展示日志和点击日志已去重
    val unionLogRdd = getTodayLogData(sc)
    // 再将获取到的日志数据由json字符串转换成训练用的格式
    val logDataResult = transformDataFormat(unionLogRdd).cache()
    // 在保存数据前，先对保存的数据设置缓存cache，然后执行一次行动操作，然后再保存
    // 即可避免出现保存路径文件夹已存在的报错问题。
    logDataResult.first()
    logDataResult.coalesce(20).saveAsTextFile(saveLogDataHdfsPromotion)
  }

  //    # 只获取当天的日志数据
  def getTodayLogData(sc: SparkContext): RDD[String] ={
    val now = new SimpleDateFormat("yyyyMMdd").format(new Date())
    val impressDirsPath = getDateHdfsDirsPath(hdfsImpressionLogPath, now)
    val clickDirsPath = getDateHdfsDirsPath(hdfsClickLogPath, now)
    // 只获取探索的日志
    val impressLogInputRdd = sc.textFile(impressDirsPath, 200)
//    val impressLogInputRdd = sc.textFile(hdfsImpressionLogFilePath, 200)
      .filter(line => try{
        JSON.parseObject(line).getJSONObject("e23").getJSONObject("o2").getInteger("e30").equals(7)
      }catch {
        case _: Throwable => false
      }).map(line => {
                var log = JSON.parseObject(line)
                val cid: String = {
                  if (log.containsKey("e23"))
                    log.getJSONObject("e23").getString("e15")
                  else
                    log.getJSONObject("e24").getString("e15")
                }
                //        val uid: String = log.getString("p7")
                val promotion: String = log.getString("p6")
                val imei: String = log.getString("p17")
                val logTime: String = log.getString("p3")
                val logTimeDayLevel: String = logTime.trim().split(" ")(0)
                (cid + imei + logTimeDayLevel, line)
              }).reduceByKey((a, b) => a)     // 对同一时间同一用户发生的多条日志去重
                .map(a => a._2)
  .cache()
    val clickLogInputRdd = sc.textFile(clickDirsPath, 200)
//    val clickLogInputRdd = sc.textFile(hdfsClickLogFilePath, 200)
      .filter(line => try{
        JSON.parseObject(line).getJSONObject("e24").getJSONObject("o2").getInteger("e30").equals(7)
      }catch {
        case _: Throwable => false
      }).map(line => {
              var log = JSON.parseObject(line)
              val cid: String = {
                if (log.containsKey("e23"))
                  log.getJSONObject("e23").getString("e15")
                else
                  log.getJSONObject("e24").getString("e15")
              }
              //        val uid: String = log.getString("p7")
              val promotion: String = log.getString("p6")
              val imei: String = log.getString("p17")
              val logTime: String = log.getString("p3")
              val logTimeDayLevel: String = logTime.trim().split(" ")(0)
              (cid + imei + logTimeDayLevel, line)
            }).reduceByKey((a, b) => a)     // 对同一时间同一用户发生的多条日志去重
              .map(a => a._2)
  .cache()

    // 将展示日志和点击日志合并
    // 根据cid、uid、imei、时间、渠道确定同一条日志
    // 在同一天、同一个IMEI点击了就算是点击了（防止同一台手机装了不同的APP）
    val unionRdd = impressLogInputRdd.union(clickLogInputRdd)
      .map(line => {
        var log = JSON.parseObject(line)
        val cid: String = {
          if (log.containsKey("e23"))    // 展示日志
            log.getJSONObject("e23").getString("e15")
          else
            log.getJSONObject("e24").getString("e15")
        }
          //        val uid: String = log.getString("p7")
          val promotion: String = log.getString("p6")
          val imei: String = log.getString("p17")
          val logTime: String = log.getString("p3")
          val logTimeDayLevel: String = logTime.trim().split(" ")(0)
          (cid + imei + logTimeDayLevel, log)     // 由于展示日志和点击日志都已经去重了，因此不会出现同一种日志出现两次的现象
        }
      ).groupByKey()     // 把拥有相同key值的log聚到一起
      .map(x => {
        val JO: JSONObject = new JSONObject()
        if (x._2.size == 1){
          val list_x = x._2.toList
          JO.put("isClick", "0")
          JO.put("log", list_x(0))
        }else {
          val list_x = x._2.toList
          val y = {
            if (list_x(0).containsKey("e23"))
              list_x(0)
            else
              list_x(1)
          }
          JO.put("isClick", "1")
          JO.put("log", y)
        }
      JO.toString
    }
      )
    return unionRdd
//    unionRdd.coalesce(2).saveAsTextFile(saveLogDataLocal)

  }

  def transformDataFormat(inputLogRdd: RDD[String]): RDD[String] ={
//    val test4 = inputLogRdd.first()
    inputLogRdd.map(line => {
      val sb: StringBuilder = new StringBuilder
      val js = JSON.parseObject(line)
      sb.append(js.getString("isClick"))
      sb.append(",")
      val log = js.getJSONObject("log")
      sb.append(log.getString("p1"))
      sb.append(",")
      sb.append(log.getString("p3"))
      sb.append(",")
      sb.append(log.getString("p5"))
      sb.append(",")
      sb.append(log.getString("p6"))
      sb.append(",")
      sb.append(log.getString("p7"))
      sb.append(",")
      sb.append(log.getString("p9"))
      sb.append(",")
      sb.append(log.getString("p10"))
      sb.append(",")
      sb.append(log.getString("p11"))
      sb.append(",")
      sb.append(log.getString("p13"))
      sb.append(",")
      sb.append(log.getString("p14"))
      sb.append(",")
      sb.append(log.getString("p17"))
      sb.append(",")
      sb.append(log.getString("p20"))
      sb.append(",")
      sb.append(log.getString("p21"))
      sb.append(",")
      sb.append(log.getString("p22"))
      sb.append(",")
      sb.append(log.getString("p23"))
      sb.append(",")
      sb.append(log.getString("p24"))
      sb.append(",")
      sb.append(log.getString("p25"))
      sb.append(",")
      val p30: JSONObject = log.getJSONObject("p30")
      if (p30.containsKey("p31"))
        sb.append(p30.getString("p31"))
      sb.append(",")
      if (p30.containsKey("p32"))
        sb.append(p30.getString("p32"))
      sb.append(",")
      if (p30.containsKey("p33"))
        sb.append(p30.getString("p33"))
      sb.append(",")
      if (p30.containsKey("e11"))
        sb.append(log.getString("e11"))
      sb.append(",")
      val e23: JSONObject = {if (log.containsKey("e23")) log.getJSONObject("e23") else log.getJSONObject("e24")}
      if (e23 == null){
        sb.append(",,,,,,,,,")
      }else{
        if (e23.containsKey("e12"))
          sb.append(e23.getString("e12"))
        sb.append(",")
        if (e23.containsKey("e14"))
          sb.append(e23.getString("e14"))
        sb.append(",")
        if (e23.containsKey("e15"))
          sb.append(e23.getString("e15"))
        sb.append(",")
        val o2: JSONObject = e23.getJSONObject("o2")
        if (o2.containsKey("e31"))
          sb.append(o2.getString("e31"))
        sb.append(",")
        if (o2.containsKey("e35"))
          sb.append(o2.getString("e35"))
        sb.append(",")
        if (o2.containsKey("e36"))
          sb.append(o2.getString("e36"))
        sb.append(",")
        if (o2.containsKey("e37")){
          val e37: JSONObject = o2.getJSONObject("e37")
          if (e37.containsKey("l1"))
            sb.append(e37.getString("l1"))
        }
        sb.append(",")
        if (o2.containsKey("e37")){
          val e37: JSONObject = o2.getJSONObject("e37")
          if (e37.containsKey("l2"))
            sb.append(e37.getString("l2"))
        }
        sb.append(",")
        if (o2.containsKey("e38"))
          sb.append(o2.getString("e38"))
        sb.append(",")
      }     // e23 结束

      val e2: JSONObject = log.getJSONObject("e2")
      if (e2.containsKey("e3"))
        sb.append(e2.getString("e3"))
      sb.append(",")
      if (e2.containsKey("e4"))
        sb.append(e2.getString("e4"))
      sb.append(",")
      if (e2.containsKey("e5"))
        sb.append(e2.getString("e5"))
      sb.append(",")
      if (e2.containsKey("e6"))
        sb.append(e2.getString("e6"))
      sb.append(",")
      if (e2.containsKey("e7"))
        sb.append(e2.getString("e7"))
      sb.append(",")
      if (e2.containsKey("e8"))
        sb.append(e2.getString("e8"))
      sb.append(",")
      if (e2.containsKey("e9"))
        sb.append(e2.getString("e9"))
      sb.append(",")
      if (e2.containsKey("e39"))
        sb.append(e2.getString("e39"))
      sb.toString()
    })
//      .coalesce(2)
//      .saveAsTextFile(saveLogDataHdfsPromotion)
  }


  // 根据输入的日期获取当天的日志
  def getDateHdfsDirsPath(hdfsLogPath: String, date: String): String ={
    hdfsLogPath + date + dirs
  }


}
