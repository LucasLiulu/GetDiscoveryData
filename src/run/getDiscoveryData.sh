#!/bin/bash

source /etc/profile

spark-submit  --master yarn-cluster\
  --executor-memory 4G \
  --executor-cores 6 \
  --num-executors 6 \
  --driver-memory 5G \
  --conf spark.yarn.executor.memoryOverhead=2048\
  --conf spark.yarn.driver.memoryOverhead=2048\
  --conf "spark.driver.extraJavaOptions=-XX:+UseParNewGC -XX:+UseConcMarkSweepGC -XX:MaxPermSize=512M -XX:+PrintGCDetails -verbose:gc -XX:+PrintGCTimeStamps" \
  --conf "spark.locality.wait=1s" \
  --conf "spark.storage.memoryFraction=0.5" \
  --conf "spark.shuffle.memoryFraction=0.3" \
  --jars lib/jackson-0.8.0.jar,lib/nd4j-jackson-0.8.0.jar,lib/deeplearning4j-nn-0.8.0.jar,lib/deeplearning4j-nlp-0.8.0.jar,lib/nd4j-api-0.8.0.jar,lib/javacpp-1.3.2.jar,lib/nd4j-common-0.8.0.jar,lib/nd4j-native-api-0.8.0.jar,lib/nd4j-native-platform-0.8.0.jar,lib/reflections-0.9.10.jar,lib/javassist-3.18.1-GA.jar,lib/nd4j-context-0.8.0.jar,lib/nd4j-buffer-0.8.0.jar,lib/tomcat-util-8.5.31.jar,lib/spring-core-4.3.14.RELEASE.jar,lib/commons-pool2-2.2.jar,lib/jedis-2.9.0.jar,lib/fastjson-1.2.35.jar,lib/elasticsearch-5.5.2.jar,lib/mysql-connector-java-5.1.41.jar,lib/slf4j-api-1.7.10.jar \
  --class com.Lucas.GetHdfsLogData lib/GetDiscoveryData-1.0-SNAPSHOT.jar 8 0
