package com.alice.matching

import java.util.Properties
import com.alice.bean.{HBaseMeta, TagRule}
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.{DataFrame, Dataset, Row, SparkSession}

/*
 * @Author: Alice菌
 * @Date: 2020/6/10 15:23
 * @Description: 

     基于用户的国籍标签进行开发

 */
object NationalityTag {

  // 程序入口

  def main(args: Array[String]): Unit = {

    // 1. 读取MySQL，Hbase中的数据
    val spark: SparkSession = SparkSession.builder().appName("JobTag").master("local[*]").getOrCreate()

    // 设置日志级别
    spark.sparkContext.setLogLevel("WARN")

    // 2. 设置Spark连接MySQL所需要的字段
    var url: String = "jdbc:mysql://bd001:3306/tags_new2?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&user=root&password=123456"
    var table: String = "tbl_basic_tag" //mysql数据表的表名
    var properties: Properties = new Properties

    // 连接MySQL
    val mysqlConn: DataFrame = spark.read.jdbc(url, table, properties)

    // 引入隐式转换
    import spark.implicits._
    //引入java 和scala相互转换
    import scala.collection.JavaConverters._
    //引入sparkSQL的内置函数
    import org.apache.spark.sql.functions._

    // 3. 读取MySQL数据库的四级标签
    val fourTagsDS: Dataset[Row] = mysqlConn.select("id", "rule").where("id=80")

    val KVMaps: Map[String, String] = fourTagsDS.map(row => {
      // 获取到rule的值
      val RuleValue: String = row.getAs("rule").toString

      // 使用“##”对数据进行切分
      val KVMaps: Array[(String, String)] = RuleValue.split("##").map(kv => {
        val arr: Array[String] = kv.split("=")
        (arr(0), arr(1))
      })
      KVMaps
    }).collectAsList().get(0).toMap

    println(KVMaps)

    // 查看Map结果
    //Map(selectFields -> id,nationality, inType -> HBase, zkHosts -> 192.168.10.20, zkPort -> 2181, hbaseTable -> tbl_users, family -> detail)


    // 通过调用自己写的封装方法，对Map数据封装成样例类
    var hbaseMeta: HBaseMeta = toHBaseMeta(KVMaps)

    // 4. 读取MySQL数据库的五级标签
    // 匹配国籍
    val fiveTagsDS: Dataset[Row] = mysqlConn.select("id", "rule").where("pid=80")

    // 将FiveTagsDS 封装成样例类TagRule
    val fiveTagsList: List[TagRule] = fiveTagsDS.map(row => {

      // row 是一条数据
      // 获取出id 和 rule
      val id: Int = row.getAs("id").toString.toInt
      val rule: String = row.getAs("rule").toString

      // 封装样例类
      TagRule(id, rule)
    }).collectAsList().asScala.toList

    // 打印查看结果
    for (a <- fiveTagsList) {
      println(a.id + "  " + a.rule)
    }

    //81  1
    //82  2
    //83  3
    //84  4
    //85  5

    // 读取hbase中的数据，这里将hbase作为数据源进行读取
    val hbaseDatas: DataFrame = spark.read.format("com.czxy.tools.HBaseDataSource")
      // hbaseMeta.zkHosts 就是 192.168.10.20  和 下面是两种不同的写法
      .option("zkHosts",hbaseMeta.zkHosts)
      .option(HBaseMeta.ZKPORT, hbaseMeta.zkPort)
      .option(HBaseMeta.HBASETABLE, hbaseMeta.hbaseTable)
      .option(HBaseMeta.FAMILY, hbaseMeta.family)
      .option(HBaseMeta.SELECTFIELDS, hbaseMeta.selectFields)
      .load()

    // 展示一些数据
    hbaseDatas.show(5)

    //+---+-----------+
    //| id|nationality|
    //+---+-----------+
    //|  1|          1|
    //| 10|          1|
    //|100|          1|
    //|101|          1|
    //|102|          1|
    //+---+-----------+

    // 需要自定义UDF函数
    val getUserTags: UserDefinedFunction = udf((rule: String) => {

      // 设置标签的默认值
      var tagId: Int = 0
      // 遍历每一个五级标签的rule
      for (tagRule <- fiveTagsList) {

        if (tagRule.rule == rule) {
          tagId = tagRule.id
        }
      }
      tagId
    })

    // 6、使用五级标签与Hbase的数据进行匹配获取标签
    val jobNewTags : DataFrame = hbaseDatas.select('id.as ("userId"),getUserTags('nationality).as("tagsId"))
    jobNewTags.show(5)

    //+------+------+
    //|userId|tagsId|
    //+------+------+
    //|     1|    81|
    //|    10|    81|
    //|   100|    81|
    //|   101|    81|
    //|   102|    81|
    //+------+------+

    /*  定义一个udf,用于处理旧数据和新数据中的数据 */
    val getAllTages: UserDefinedFunction = udf((genderOldDatas: String, jobNewTags: String) => {

      if (genderOldDatas == "") {
        jobNewTags
      } else if (jobNewTags == "") {
        genderOldDatas
      } else if (genderOldDatas == "" && jobNewTags == "") {
        ""
      } else {
        val alltages: String = genderOldDatas + "," + jobNewTags  //可能会出现 83,94,94
        // 对重复数据去重
        alltages.split(",").distinct // 83 94
          // 使用逗号分隔，返回字符串类型
          .mkString(",") // 83,84
      }
    })


    // 7、解决数据覆盖的问题
    // 读取test，追加标签后覆盖写入
    // 标签去重
    val genderOldDatas: DataFrame = spark.read.format("com.czxy.tools.HBaseDataSource")
      // hbaseMeta.zkHosts 就是 192.168.10.20  和 下面是两种不同的写法
      .option("zkHosts","192.168.10.20")
      .option(HBaseMeta.ZKPORT, "2181")
      .option(HBaseMeta.HBASETABLE, "test")
      .option(HBaseMeta.FAMILY, "detail")
      .option(HBaseMeta.SELECTFIELDS, "userId,tagsId")
      .load()

    // 展示一些数据
    genderOldDatas.show(5)

    //+------+------+
    //|userId|tagsId|
    //+------+------+
    //|     1|  6,68|
    //|    10|  6,70|
    //|   100|  6,68|
    //|   101|  5,66|
    //|   102|  6,66|
    //+------+------+

    // 新表和旧表进行join
    val joinTags: DataFrame = genderOldDatas.join(jobNewTags, genderOldDatas("userId") === jobNewTags("userId"))

    val allTags: DataFrame = joinTags.select(
      // 处理第一个字段
      when((genderOldDatas.col("userId").isNotNull), (genderOldDatas.col("userId")))
        .when((jobNewTags.col("userId").isNotNull), (jobNewTags.col("userId")))
        .as("userId"),

      getAllTages(genderOldDatas.col("tagsId"), jobNewTags.col("tagsId")).as("tagsId")
    )

    allTags.show(5)

    //+------+-------+
    //|userId| tagsId|
    //+------+-------+
    //|   296|5,71,81|
    //|   467|6,71,81|
    //|   675|6,68,81|
    //|   691|5,66,81|
    //|   829|5,70,81|

    // 将最终结果进行覆盖
    allTags.write.format("com.czxy.tools.HBaseDataSource")
      .option("zkHosts", hbaseMeta.zkHosts)
      .option(HBaseMeta.ZKPORT, hbaseMeta.zkPort)
      .option(HBaseMeta.HBASETABLE,"test")
      .option(HBaseMeta.FAMILY, "detail")
      .option(HBaseMeta.SELECTFIELDS, "userId,tagsId")
      .save()

  }

  //将mysql中的四级标签的rule  封装成HBaseMeta
  //方便后续使用的时候方便调用
  def toHBaseMeta(KVMap: Map[String, String]): HBaseMeta = {
    //开始封装
    HBaseMeta(KVMap.getOrElse("inType", ""),
      KVMap.getOrElse(HBaseMeta.ZKHOSTS, ""),
      KVMap.getOrElse(HBaseMeta.ZKPORT, ""),
      KVMap.getOrElse(HBaseMeta.HBASETABLE, ""),
      KVMap.getOrElse(HBaseMeta.FAMILY, ""),
      KVMap.getOrElse(HBaseMeta.SELECTFIELDS, ""),
      KVMap.getOrElse(HBaseMeta.ROWKEY, "")
    )

  }
}
