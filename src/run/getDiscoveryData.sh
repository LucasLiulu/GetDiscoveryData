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
  --jars lib/tomcat-util-8.5.31.jar,lib/spring-core-4.3.14.RELEASE.jar,lib/commons-pool2-2.2.jar,lib/jedis-2.9.0.jar,lib/fastjson-1.2.35.jar,lib/elasticsearch-5.5.2.jar,lib/mysql-connector-java-5.1.41.jar,lib/slf4j-api-1.7.10.jar\
  --class com.Lucas.GetHdfsLogData lib/GetDiscoveryData-1.0-SNAPSHOT.jar 2
