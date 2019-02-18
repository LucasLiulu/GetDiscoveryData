package com.Lucas
/*
* 根据content ID去数据库中获取资讯数据
 */
import java.sql.{Connection, DriverManager}
import java.util.{Calendar, Date, Timer, TimerTask}
import java.text.SimpleDateFormat

import com.alibaba.fastjson.{JSON, JSONObject}
import org.apache.log4j.{Level, Logger}
import config._
import org.apache.spark.rdd.RDD
import org.apache.spark.{SparkConf, SparkContext}
import java.net.URI
import java.io.{File, InputStream}

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileSystem, Path}
import org.deeplearning4j.models.embeddings.wordvectors.WordVectors
import org.deeplearning4j.models.embeddings.loader.WordVectorSerializer
import com.Lucas.textCosineProcess.addVectorAndWeight

object GetDatabaseData {
  val logger: Logger = Logger.getLogger(GetDatabaseData.getClass)

  var connection: Connection = null
  var cacheMap: Map[String, String] = null

  var tagsCacheMap: Map[String, String] = null
  var tagsTitleCacheMap: Map[String, String] = null

  /**
    * body_images_count
    * image_auditstate
    * publisher_score
    * publish_time
    * content_words_num
    * title_words_num
    * country
    * city
    * state
    * ero_cal_result
    * group_id
    */
  val EXTRA = ", " + ", " + ", " + ", " + ", " + ", " + ", " + ", " + ", " + ", " + ", "

  def main(args: Array[String]): Unit = {
    val sparkConf: SparkConf = new SparkConf().setAppName("GetDatabaseData")
    var runType = "spark"
    if (args.length == 0){
      runType = "local"
      sparkConf.setMaster("local")
    }
    val sc: SparkContext = new SparkContext(sparkConf)
    sc.setLogLevel("WARN")
    println("test")
    // 读取已查询到的资讯数据
    val contentOldInfo: RDD[String] = sc.textFile(saveContentInfoHdfs + "20190215/*.csv", 20)
    val cidOldRdd: RDD[String] = contentOldInfo.map(line => line.split(",")(0))
    val cid: RDD[String] = sc.textFile(saveLogDataHdfsPromotion + "*/part*", 20)
      .map(line => (line.split(",")(24), line))
      .reduceByKey((a, b) => a)
      .map(g => g._2)
      .map(line => line.split(",")(24))
    // 得到新的日志文件的cid和老资讯数据中的cid的差集，只保留新的cid
    val cidSubtract = cid.subtract(cidOldRdd)
    // 为了避免一次查询量过大，将查询任务分成5组执行
    val cidArr = cidSubtract.randomSplit(Array(0.2, 0.2, 0.2, 0.2, 0.2))
    val contentInfo0 = cidArr(0).map(cid => getContentInfo(cid)).cache()
    processSleep(2000)  // 暂停2s
    val contentInfo1 = cidArr(1).map(cid => getContentInfo(cid)).cache()
    processSleep(2000)
    val contentInfo2 = cidArr(2).map(cid => getContentInfo(cid)).cache()
    processSleep(2000)
    val contentInfo3 = cidArr(3).map(cid => getContentInfo(cid)).cache()
    processSleep(2000)
    val contentInfo4 = cidArr(4).map(cid => getContentInfo(cid)).cache()
    processSleep(2000)
    val contentInfo = contentInfo0.union(contentInfo1).union(contentInfo2)
      .union(contentInfo3).union(contentInfo4)
    // 再将当前查询到的资讯数据与原有的资讯数据合并
      //由于查询数据库时只查了仅存在于新资讯中的资讯数据，因此需要找到原有资讯和当前日志中的资讯的交集
// 最后与当前从数据库中查询到的资讯数据进行合并
    val contentInfoIntersection = cid.intersection(cidOldRdd)  // 得到交集
      .union(contentOldInfo)
      .groupBy(line => line.split(",")(0))
      .filter(a => a._2.size == 2)
      .map(a => {
        val arrTmp = a._2.toList
        if (arrTmp(0).size > 23) arrTmp(0) else if (arrTmp(1).size>23) arrTmp(1) else ""
      }).filter(a => !"".equals(a))
    // 将当前日志数据中的content ID对应的资讯数据和交集部分的资讯数据合并
    val contentInfoResult = contentInfo.union(contentInfoIntersection)
    val now: String = new SimpleDateFormat("yyyyMMddHHmm").format(new Date())
    logger.warn("now: " + now)
    try{
      val contentResult = contentInfoResult.coalesce(20).cache()
      contentResult.saveAsTextFile(saveContentInfoHdfs + now)
      logger.warn("save content info success!")
    }catch {
      case _: Throwable => logger.error("fuck error!!!")
    }
    logger.warn("save content info finished!!!")
//    // 将文章关键字转换成词向量，标题和文章主体关键字
//    val cInfo = sc.textFile(saveContentInfoHdfs, 20)
//    val wvt: RDD[String] = wordVec4Tags(cInfo, runType).cache()
//    logger.warn("wvt first: " + wvt.first().toString)
//    wvt.coalesce(10).saveAsTextFile(saveContentWordVecHdfs)
  }

  // 第二种方法还有点问题
  def processSleep(sec: Int, simple: Boolean=true): Unit ={
    if (simple){
      try{
        Thread.sleep(sec)
      }catch {
        case _: Throwable => None
      }
    }else{
      val timer: Timer = new Timer()
      timer.schedule(new TimerTask {

        override def run(): Unit = {
          println("退出")
          this.cancel()
        }
      }, sec)
    }
  }
  // 获取词向量
  def wordVec4Tags(contentInfo: RDD[String], runType: String): RDD[String] ={
    val wordVectors: WordVectors =
      if ("local".equals(runType)) {
        println("local run type....")
        WordVectorSerializer.loadTxtVectors(new File(vectPathLocal))
      }else{
        val conf: Configuration = new Configuration()
        val fs: FileSystem = FileSystem.get(URI.create(vectPath), conf)
        val inputStream: InputStream = fs.open(new Path(vectPath)).getWrappedStream
        WordVectorSerializer.loadTxtVectors(inputStream, true)
      }

    contentInfo.map(line => {
      val tmpList = line.split(",")
      val cid: String = tmpList(0)
      //      曼联=0.418 巴黎圣日耳曼=0.409 欧冠=0.403 大巴黎=0.379 进球=0.375 客场=0.353 首回合=0.338 淘汰赛=0.325
      val tagsStr = tmpList(12)
//      庞贝=1.01 于进=1.01 首开=0.6 大巴黎=0.6
      val tagsTitleStr = tmpList(13)
      // 得到加权后的词向量
      val tagsWordVec = addVectorAndWeight(tagsStr, wordVectors)
      val tagsTitleWordVec = addVectorAndWeight(tagsTitleStr, wordVectors)
      cid + "," + tagsWordVec.toString + "," + tagsTitleWordVec.toString
    })

  }

  def getContentInfo(cid: String): String ={
    if (cacheMap == null){
      cacheMap = Map()
    }
    if (tagsCacheMap == null){
      tagsCacheMap = Map()
    }
    if (tagsTitleCacheMap == null){
      tagsTitleCacheMap = Map()
    }
    var tagsStr = ""
    var tagsTitleStr = ""

    var value = ""
    if (null != cacheMap && cacheMap.contains(cid)){
      value = cacheMap(cid)
    }
    if ("".equals(value) || value == null){
      val sql: String = getSql(cid)
      logger.info("sql: " + sql)

      if (!"".equals(sql)){
        if(connection==null){
          Class.forName(driver)
          connection = DriverManager.getConnection(mysqlUrl, username, password)
        }
        if (null == connection){
          logger.error("connect database failed!!!")
        }
        val statement = connection.createStatement()
        try{
          statement.executeQuery(sql)
        }catch {
          case ex: Throwable =>
            logger.error("query sql raised an error: " + sql)
        }
        val rs = statement.executeQuery(sql)

        if (rs != null && rs.next()){
          val content_id = rs.getString("content_id")
          val body_images_count = rs.getString("body_images_count")
          val image_auditstate = rs.getString("image_auditstate")
          val publisher_score = if (sql.contains("publisher_score"))
            rs.getString("publisher_score")
          else " "
          val publish_time = rs.getString("publish_time")
          val content_words_num = rs.getString("content_words_num")
          val title_words_num = rs.getString("title_words_num")
          val local = rs.getString("local")
          var theLocal = " , , "
          val jsonObj: JSONObject = JSON.parseObject(local)
          if (jsonObj != null && jsonObj.containsKey("v1")){
            val jarr = jsonObj.getJSONArray("v1")
            if (jarr != null && jarr.size() > 0){
              val item = jarr.getJSONObject(0)
              if (item != null){
                var country = item.getString("country")
                if (country == null){
                  country = " "
                }
                var city = item.getString("city")
                if (city == null){
                  city = " "
                }
                var state = item.getString("state")
                if (state == null){
                  state = " "
                }
                theLocal = country + "," + city + "," + state
              }
            }
          }
          var ero_cal_result = " "
          if (sql.contains("ero_cal_result")){
            ero_cal_result = rs.getString("ero_cal_result")
          }
          val group_id = rs.getString("group_id")
          val groupJObj: JSONObject = JSON.parseObject(group_id)
          val groupKeySet = groupJObj.getJSONObject("v2").keySet()
          val itt = groupKeySet.iterator()
          var groupIdKey1 = " "
          if (itt.hasNext){
            groupIdKey1 = itt.next()
          }

          val temp = "," + body_images_count +
          "," + image_auditstate +
          "," + publisher_score +
          "," + publish_time +
          "," + content_words_num +
          "," + title_words_num +
          "," + theLocal +
          "," + ero_cal_result +
          "," + groupIdKey1

          cacheMap += (content_id -> temp)
          value = temp

          tagsStr = rs.getString("tags")
          tagsTitleStr = rs.getString("tags_title")

        }else{
          value = EXTRA
        }
      }else{
        value = EXTRA
      }
    }

    var tags = ""
    var tagsTitle = ""
//    val ifLoadKeyWords = 1
//    if (ifLoadKeyWords > 0){
      if (null != tagsCacheMap && tagsCacheMap.contains(cid)){
        tags = tagsCacheMap(cid)
      }
      if ("".equals(tags) || tags == null){
        tags = formateTags(tagsStr, "{\"v8\":{\"")
        tagsCacheMap += (cid -> tags)
      }
      if (null != tagsTitleCacheMap && tagsTitleCacheMap.contains(cid)){
        tagsTitle = tagsTitleCacheMap(cid)
      }
      if ("".equals(tagsTitle) || null == tagsTitle){
        tagsTitle = formateTags(tagsTitleStr, "{\"v4\":{\"")
        tagsTitleCacheMap += (cid -> tagsTitle)
      }
//    }
    cid + value + "," + tags + "," + tagsTitle
  }

  def formateTags(str: String, key: String): String = str.replace(key, "")
    .replace("\":{\"weight\":", "=")
    .replace("},\"", " ")
    .replace("}}}", "")

  def getSql(cid: String): String ={
    val date: String = getStringDate(cid)
    if (!"".equals(date)){
//      if (Integer.parseInt(date.replace("_", "")) < 201808){
//        ""
//      }

      var sql = "SELECT " +
        "t_content.content_id," +
        "t_content.body_images_count," +
        "t_content.image_auditstate," +
        "t_content.publisher_score," +
        "t_content.publish_time," +
        "t_content.content_type," +
        "t_signal.content_words_num, " +
        "t_signal.title_words_num, " +
        "t_signal.local, " +
        "t_signal.group_id, " +
        "t_signal.tags, " +
        "t_signal.tags_title, " +
        "t_news_manager.ero_cal_result " +
        "FROM t_content_" + date + " t_content " +
        "INNER JOIN t_signal_" + date + " t_signal " +
        "INNER JOIN t_news_manager_" + date + " t_news_manager ON t_content.content_id = t_signal.content_id " +
        "AND t_content.content_id = t_news_manager.content_id " +
        "WHERE " +
        "t_content.content_id = " + cid

      if (Integer.parseInt(date.replace("_", "")) % 100 < 8){
        sql = "SELECT " +
          "t_content.content_id," +
          "t_content.body_images_count," +
          "t_content.image_auditstate," +
          "t_content.publish_time," +
          "t_content.content_type," +
          "t_signal.content_words_num, " +
          "t_signal.title_words_num, " +
          "t_signal.local, " +
          "t_signal.group_id, " +
          "t_signal.tags, " +
          "t_signal.tags_title, " +
          "t_news_manager.ero_cal_result " +
          "FROM t_content_" + date + " t_content " +
          "INNER JOIN t_signal_" + date + " t_signal " +
          "INNER JOIN t_news_manager_" + date + " t_news_manager ON t_content.content_id = t_signal.content_id " +
          "AND t_content.content_id = t_news_manager.content_id " +
          "WHERE " +
          "t_content.content_id = " + cid
      }
      return sql
    }
    ""
  }

  def getStringDate(cid: String): String ={
    if (!isNumeric(cid)){
      ""
    }
    try{
      val yearPart: String = cid.substring(0, 4)
      val monthParh: Int = Integer.parseInt(cid.substring(4, 6))
      if (("2018" == yearPart && monthParh < 8) || (!"2018".equals(yearPart) && !"2019".equals(yearPart)))
        ""
      else
        cid.substring(0, 4) + "_" + cid.substring(4, 6)
    }catch {
      case ex: Throwable =>
        ""
    }
  }

  def isNumeric(str: String): Boolean = if (str==null) false
  else {
    val sz = str.length
    var i = 0
    while ({i < sz}){
      if (!Character.isDigit(str.charAt(i))) return false

      i += 1
      i

    }
    true
  }
}
