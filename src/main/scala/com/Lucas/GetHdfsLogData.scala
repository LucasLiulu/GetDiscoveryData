package com.Lucas

/*
* 只获取日志数据
 */
import com.Lucas.config._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.{SparkConf, SparkContext}
import java.util.{Calendar, Date, ArrayList}
import java.text.SimpleDateFormat
import com.Lucas.GetDatabaseData.getDBMainLogical

import org.apache.hadoop.conf.Configuration
import com.alibaba.fastjson.JSONObject
import com.alibaba.fastjson.JSON
import org.apache.spark.rdd.RDD
import com.Lucas.GetDatabaseData.getContentInfo
import org.apache.hadoop.fs.{FileSystem, Path}

object GetHdfsLogData {
  val logger: Logger = Logger.getLogger(GetHdfsLogData.getClass)
  val conf: Configuration = new Configuration()

  def main(args: Array[String]): Unit = {
    val sparkConf: SparkConf = new SparkConf().setAppName("GetHdfsLogData")
    var runType = "spark"
    if (args.length == 0){
      runType = "local"
      sparkConf.setMaster("local")
    }
    val sc: SparkContext = new SparkContext(sparkConf)
    sc.setLogLevel("WARN")
    // 从启动脚本传入需要获取多少天的日志数据
    val howManyDaysLog: Int = if (args.length > 0) Integer.parseInt(args(0)) else 1
    // 从哪天开始获取，默认是0，即从今天开始获取；如果是1则从昨天开始获取。
    val fromWhichDay: Int = if (args.length > 1) Integer.parseInt(args(1)) else 0
//    val impressDirsPathList: Array[String] = getDateHdfsDirsPathList(hdfsImpressionLogPath, howManyDaysLog, fromWhichDay)
//    val clickDirsPathList = getDateHdfsDirsPathList(hdfsClickLogPath, howManyDaysLog, fromWhichDay)
    var i: Int = 0
    val savePathList: StringBuilder = new StringBuilder()
    // 循环获取日志数据，并按日志的日期存储
    while (i < howManyDaysLog){
      // 确定获取哪天的日志
      val pastDay: Int = i + fromWhichDay
      val date: String = getDate(pastDay)
      // 确定要获取的日志的路径
      val impressDirsPath = getDateHdfsDirsPath(hdfsImpressionLogPath, date)
      val clickDirsPath = getDateHdfsDirsPath(hdfsClickLogPath, date)
      // 只获取当天的日志数据，展示日志及每条日志是否点击
      // 展示日志和点击日志已去重
      val unionLogRdd = getTodayLogData(sc, impressDirsPath, clickDirsPath)
      // 再将获取到的日志数据由json字符串转换成训练用的格式
      val logDataResult = transformDataFormat(unionLogRdd)
      val saveResult = logDataResult.coalesce(500)
//        .cache()
      // 在保存数据前，先对保存的数据设置缓存cache，然后执行一次行动操作，然后再保存
      // 即可避免出现保存路径文件夹已存在的报错问题。
//      logger.warn("saveResult.first(): " )
//      logger.warn(saveResult.first().toString)
      logger.warn("date: " + date)
      val savePath: String = saveLogDataHdfsPrefix + getDate(pastDay, true) + "/onlyLogData/" + date
      saveResult.saveAsTextFile(savePath)
      if (i!=0){
        savePathList.append(",")
      }
      savePathList.append(savePath + "/*")
      i += 1
    }
    logger.warn("savePathList toString: " + savePathList.toString())
    getDBMainLogical(sc: SparkContext, onlyLogPath=savePathList.toString())

  }

  // 从数据库查询日志中出现过的文章的数据
  def getDatabaseContentData(sc: SparkContext): RDD[String] =
    sc.textFile(saveLogDataHdfsPromotion + "*/part*", 1024)
      .map(line => line.split(",")(24))
      .map(cid => getContentInfo(cid))

  //    只获取当天的日志数据
  def getTodayLogData(sc: SparkContext, impressDirsPath: String, clickDirsPath: String): RDD[String] ={
//    val now = new SimpleDateFormat("yyyyMMdd").format(new Date())
//    val impressDirsPath = getDateHdfsDirsPath(hdfsImpressionLogPath, now)
//    val clickDirsPath = getDateHdfsDirsPath(hdfsClickLogPath, now)
    // 只获取探索的日志
    val impressLogInputRdd = sc.textFile(impressDirsPath, 1024)
      .filter(line => try{
        line.contains("e23") && JSON.parseObject(line).getJSONObject("e23").getJSONObject("o2").getInteger("e30").equals(GMP_STRATEGY_NUM)
      }catch {
        case _: Throwable => false
      }).map(line => {
                val log = JSON.parseObject(line)
                val cid: String = log.getJSONObject("e23").getString("e15")
                //        val uid: String = log.getString("p7")
                val promotion: String = log.getString("p6")
                val imei: String = log.getString("p17")
                val logTime: String = log.getString("p3")
                val logTimeDayLevel: String = logTime.trim().split(" ")(0)
                (cid + imei + logTimeDayLevel, line)
              }).reduceByKey((a, b) => a)     // 对同一时间同一用户发生的多条日志去重
                .map(a => a._2)

    val clickLogInputRdd = sc.textFile(clickDirsPath, 1024)
      .filter(line => try{
        line.contains("e24") && JSON.parseObject(line).getJSONObject("e24").getJSONObject("o2").getInteger("e30").equals(GMP_STRATEGY_NUM)
      }catch {
        case _: Throwable => false
      }).map(line => {
              var log = JSON.parseObject(line)
              val cid: String = log.getJSONObject("e24").getString("e15")
              //        val uid: String = log.getString("p7")
              val promotion: String = log.getString("p6")
              val imei: String = log.getString("p17")
              val logTime: String = log.getString("p3")
              val logTimeDayLevel: String = logTime.trim().split(" ")(0)
              (cid + imei + logTimeDayLevel, line)
            }).reduceByKey((a, b) => a)     // 对同一时间同一用户发生的多条日志去重
              .map(a => a._2)

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
      )
      .groupByKey()     // 把拥有相同key值的log聚到一起
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
        if (e23.containsKey("e15")){
          sb.append(e23.getString("e15"))
        }
        sb.append(",")
        val o2: JSONObject = e23.getJSONObject("o2")
        if (o2.containsKey("e31"))
          try{
            sb.append(o2.getString("e31"))
          }catch {
            case _: Throwable => None
          }
        sb.append(",")
        if (o2.containsKey("e35"))
          sb.append(o2.getString("e35"))
        sb.append(",")
        if (o2.containsKey("e36"))
          try{
            sb.append(o2.getString("e36"))
          }catch {
            case _: Throwable => None
          }
        sb.append(",")
        if (o2.containsKey("e37")){
          try{
            val e37: JSONObject = o2.getJSONObject("e37")
            if (e37.containsKey("l1"))
              sb.append(e37.getString("l1"))
          }catch {
            case _: Throwable => None
          }
        }
        sb.append(",")
        if (o2.containsKey("e37")){
          try{
            val e37: JSONObject = o2.getJSONObject("e37")
            if (e37.containsKey("l2"))
              sb.append(e37.getString("l2"))
          }catch {
            case _: Throwable => None
          }
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

//      val cid = e23.getString("e15")
//      val contentInfo = if ("".equals(cid)) ", , , , , , , , , , , ,," else getContentInfo(cid)
//      sb.append(",contentInfoBelow,")
//      sb.append(contentInfo)

      sb.toString()
    })
//      .coalesce(2)
//      .saveAsTextFile(saveLogDataHdfsPromotion)
  }

  // 根据输入的日期获取当天的日志
  def getDateHdfsDirsPath(hdfsLogPath: String, date: String): String ={
    hdfsLogPath + date + dirs
  }

  // 从第fromWhichDay天开始获取，一共获取howManyDaysLog天的日志
  // 返回这几天的日志的路径列表
  def getDateHdfsDirsPathList(hdfsLogPath: String, howManyDaysLog: Int, fromWhichDay: Int): Array[String] ={
    val arr: Array[String] = new Array[String](howManyDaysLog)
    for (n <- fromWhichDay to howManyDaysLog + fromWhichDay){
      arr.addString(new StringBuilder(hdfsLogPath + getDate(n) + dirs))
    }
    arr
  }

  // 获取前第n天的日期，n从0开始；如果只要求返回年月，则month为true
  def getDate(n: Int, month: Boolean=false): String ={
    val dt: Date = new Date()
    val cal: Calendar = Calendar.getInstance()
    cal.setTime(dt)
    cal.add(Calendar.DATE, -n)
    if (month)
      new SimpleDateFormat("yyyyMM").format(cal.getTime)
    else
      new SimpleDateFormat("yyyyMMdd").format(cal.getTime)
  }

  def getPassedDayHdfsDirsPath(hdfsPathRequest: String, dayNum: Int, passedDay: Int, dirsString: String=dirs): String ={
    val path = StringBuilder.newBuilder
    for (x <- 1 + passedDay to dayNum + passedDay){
      val hdfsPath = hdfsPathRequest + getDate(x)
      if (isHdfsDirectory(hdfsPath)){
        path.append(hdfsPath + dirsString)
        if (x != dayNum + passedDay){
          path.append(",")
        }
      }
    }
    logger.info("in getHdfsDirsPath, after scan hdfsPath. path: " + path)
    if (path.length > 0 && path.substring(path.length -1).equals(",")){
      path.deleteCharAt(path.length - 1)
    }
    logger.info("in getHdfsDirsPath, after delete last commit. path: " + path)
    path.toString()
  }

  def isHdfsDirectory(dirPath: String): Boolean ={
    try{
      val fileSystem: FileSystem = FileSystem.get(conf)
      val path: Path = new Path(dirPath)
      fileSystem.isDirectory(path)
    }catch {
      case _: Throwable => false
    }
  }

  def isHdfsFile(filePath: String): Boolean ={
    try{
      val fileSystem: FileSystem = FileSystem.get(conf)
      val path: Path = new Path(filePath)
      fileSystem.isFile(path)
    }catch {
      case _: Throwable => false
    }
  }

}
