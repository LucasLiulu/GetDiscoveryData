package com.Lucas

import org.apache.spark.SparkContext
import org.deeplearning4j.models.embeddings.wordvectors.WordVectors


object textCosineProcess {
  def main(args: Array[String]): Unit = {
    val s1 = "817:-1;193:-1;182:-1;822:-1;124:-1;813:-1;806:-1;829:-1;141:男子=2.607 餐馆=1.953 老虎=1.919 检查=1.738 走近=1.275"
    val s2 = "民国=1.0 蒋瑶光=1.0 陆久之=1.0 中国近代史=1.0 人文=1.0 蒋介石=1.0 陈洁如=1.0"
    val a1 = Array[Double](1.0, 2.0, 3.0)
    val a2 = Array[Double](1.0, 2.0, 3.0)
    val all = addAllEle(a1)
    println(all)
    println(a1)
  }
  
  /*
  * keyword：
  * 817:-1;193:-1;182:-1;822:-1;124:-1;813:-1;806:-1;829:-1;141:男子=2.607 餐馆=1.953 老虎=1.919 检查=1.738 走近=1.275
  * tags：
  * 民国=1.0 蒋瑶光=1.0 陆久之=1.0 中国近代史=1.0 人文=1.0 蒋介石=1.0 陈洁如=1.0
  *
  * 四种可能：
  * s1: "" tagsTitle: ""
  * s1: 非空 tagsTitle: ""
  * s1: "" tagsTitle: 非空
  * s1, s2：非空
   */
  def assembleEmbeddingFileArray(wordVectors: WordVectors, s1: String, tagsTitle: String=""): Array[Double] ={
    var tmpStr = ""
    val newResultArr = new Array[Double](300)    // 词嵌入向量是300维
    if ("".equals(s1) && "".equals(tagsTitle)){
      return newResultArr
    }
    if ("".equals(tagsTitle) && isNumeric(s1.split(":")(0))){
      // keyword 的字符串
      val arr1 = s1.split(";")
      for (i <- 0 to arr1.length - 1){
        tmpStr = arr1(i).split(":")(1).trim        // 这个是category后的keyword和value字符串
          if (!"-1".equals(tmpStr)) {
            val tmpArr = addVectorAndWeight(tmpStr, wordVectors)
            for (j <- 0 to newResultArr.length -1){
              newResultArr(j) += tmpArr(j)
            }
          }
        }
      }else{
      // 否则s1 就是tags
      // tags 可能为空
      if (!"".equals(s1)){
        val tmpArr = addVectorAndWeight(s1, wordVectors)
        for (m <- 0 to newResultArr.length -1){
          newResultArr(m) += tmpArr(m)
        }
      }
    }

    // tagsTitle 字符串
    if (!"".equals(tagsTitle)){
      val tmpArr = addVectorAndWeight(tagsTitle, wordVectors)
      for (n <- 0 to newResultArr.length -1){
        newResultArr(n) += tmpArr(n)
      }
    }
    newResultArr
  }

  /*
  * 将字符串中的字符的vector累加，并考虑weight值
  * tmpStr:
  * 男子=2.607 餐馆=1.953 老虎=1.919 检查=1.738 走近=1.275
   */
  def addVectorAndWeight(tmpStr: String, wordVectors: WordVectors): Array[Double] ={
    if ("".equals(tmpStr.trim)){
      return new Array[Double](300)
    }

    var key = ""
    var value = 1.0
    val tmpArr = tmpStr.trim.split(" ")
    val returnArr = new Array[Double](300)
    for (j <- 0 to tmpArr.length - 1){
      key = tmpArr(j).split("=")(0)
      if (tmpArr(j).contains("=") && !"".equals(tmpArr(j).split("=")(1).trim)){
        value = tmpArr(j).split("=")(1).trim.toDouble
      }else{
        value = 1.0
      }
      val newkeyVector = findKeyVectorNLP(wordVectors, key, value)
      for (i <- 0 to returnArr.length -1){
        returnArr(i) += newkeyVector(i)
      }
    }
    returnArr
  }

  /*
  * 使用深度学习库nl4j查询，若词向量中没有此单词，则返回所有元素为0 的数组
   */
  def findKeyVectorNLP(wordVectors: WordVectors, key: String, value: Double): Array[Double] ={
    if ("".equals(key.trim)){
      return new Array[Double](300)
    }
    try{
      val retVec = new Array[Double](300)
      val vec = wordVectors.getWordVector(key)
      if (null == vec){
        return new Array[Double](300)
      }
      for (i <- 0 to vec.length - 1){
        retVec(i) = vec(i) * value
      }
      retVec
    }catch {
      case ex: Throwable => {
        ex.printStackTrace()
        new Array[Double](300)
      }
    }
  }

  /*
  * 使用广播大变量 embeddingWordsRdd ，使用简单循环从rdd中查询key对应的向量
  * 效率太低!!!
   */
  def findKeyVectorBroadCast (embeddingWordsRdd: Array[String], key: String, value: Double): Array[Double] ={
    if ("".equals(key.trim)){
      return new Array[Double](300)
    }

//    val loop = new Breaks
    var arr = new Array[Double](300)
    for (s <- embeddingWordsRdd){
      val dataList = s.split(" ")
      if (dataList(0).equals(key)){
        var i: Int = 0
        for (t <- 1 to dataList.length - 1){
          arr(i) = dataList(t).toDouble
          i += 1
        }
        return arr
      }
    }
    arr
  }

  def isNumeric(str: String): Boolean = if (str == null) false
  else {
    val sz = str.length
    var i = 0
    while ( {
      i < sz
    }) {
      if (!Character.isDigit(str.charAt(i))) return false

      {
        i += 1
        i
      }
    }
    true
  }

  def word2Vector(sc: SparkContext, vectPath: String, key: String): Unit ={
    val embeddingWords = sc.textFile(vectPath, 200).cache()
    val arr = embeddingWords.filter(line => {
      line.split(" ")(0).equals(key)
    }).map(line => {
      val l = line.split(" ")
      val end = l.length - 1
      l.slice(1, end).map(_.toDouble)
    }).first()
    println("xxxxxxxxxx: " + module(arr))
    println()
  }

  def module(vector: Array[Double]): Double ={
    math.sqrt(vector.map(math.pow(_, 2)).sum)
  }

  def addAllEle(vector: Array[Double]): Double ={
    vector.reduce((a, b) => math.abs(a) + math.abs(b))
  }

  def textCosine(fileVector1: Array[Double], fileVector2: Array[Double]): Double ={
    val upMember = fileVector1.zip(fileVector2).map(d => d._1 * d._2).reduce(_ + _).toDouble
//        println("upMember: " + upMember)
    val down1 = math.sqrt(fileVector1.map(num => {math.pow(num, 2)}).reduce(_ + _))
//        println("down1: " + down1)
    val down2 = math.sqrt(fileVector2.map(num => {math.pow(num, 2)}).reduce(_ + _))
//        println("down2: " + down1)
    val outCos = if (down1 * down2 > 0) upMember / (down1 * down2) else 0.0
//        println("textCosine out: " + outCos)
//    if (down1 * down2 == 0.0){
//      println("fileVector1: " + fileVector1.mkString(" "))
//      println("fileVector2: " + fileVector2.mkString(" "))
//    }
    outCos
  }

}
