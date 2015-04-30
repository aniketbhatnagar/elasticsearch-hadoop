package org.elasticsearch.spark.sql

import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.collection.mutable.LinkedHashMap

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.types.StructType
import org.elasticsearch.hadoop.cfg.ConfigurationOptions
import org.elasticsearch.hadoop.util.StringUtils
import org.elasticsearch.spark.cfg.SparkSettingsManager
import org.apache.spark.sql.sources._

private[sql] class DefaultSource extends RelationProvider {
  override def createRelation(
    @transient sqlContext: SQLContext,
    parameters: Map[String, String]): BaseRelation = {

    // . seems to be problematic when specifying the options
    val params = parameters.map { case (k, v) => (k.replace('_', '.'), v)}. map { case (k, v) =>
      if (k.startsWith("es.")) (k, v)
      else if (k == "path") ("es.resource", v)
      else ("es." + k, v) }
    params.getOrElse("es.resource", sys.error("resource must be specified for Elasticsearch resources."))
    ElasticsearchRelation(params)(sqlContext)
  }
}

private[sql] case class ElasticsearchRelation(parameters: Map[String, String])(@transient val sqlContext: SQLContext)
  extends BaseRelation with PrunedFilteredScan //with InsertableRelation
  {

  @transient lazy val cfg = {
    new SparkSettingsManager().load(sqlContext.sparkContext.getConf).merge(parameters.asJava)
  }

  @transient lazy val lazySchema = {
    MappingUtils.discoverMapping(cfg).asInstanceOf[StructType]
  }

  override val schema = lazySchema

  // TableScan
  def buildScan() = new ScalaEsRowRDD(sqlContext.sparkContext, parameters)

  // PrunedScan
  def buildScan(requiredColumns: Array[String]) = {
    val paramWithProjection = LinkedHashMap[String, String]() ++ parameters
    paramWithProjection += (ConfigurationOptions.ES_SCROLL_FIELDS -> StringUtils.concatenate(requiredColumns.asInstanceOf[Array[Object]], ","))
    new ScalaEsRowRDD(sqlContext.sparkContext, paramWithProjection)
  }
  
  // PrunedFilteredScan
  def buildScan(requiredColumns: Array[String], filters: Array[Filter]) = {
    val paramWithFilter = LinkedHashMap[String, String]() ++ parameters
    paramWithFilter += (ConfigurationOptions.ES_SCROLL_FIELDS -> StringUtils.concatenate(requiredColumns.asInstanceOf[Array[Object]], ","))
    
    val esQueries = filters.map(filter => buildQuery(filter)).filter(query => query.trim().length() > 0)
    val esQuery = esQueries.mkString("""
      {
        "query": {
          "filtered": {
            "filter": {
              "and": {
                "filters": [
      """, 
      ",", 
      """
                ]
              }
            }
          }
        }
      }""")
    paramWithFilter += (ConfigurationOptions.ES_QUERY -> esQuery)
   
    new ScalaEsRowRDD(sqlContext.sparkContext, paramWithFilter)
  }

  def insert(data: DataFrame, overwrite: Boolean) {
    throw new UnsupportedOperationException()
  }
  
  private def buildQuery(filter: Filter): String = {
    filter match {
      case EqualTo(attribute, value)            => s"""{ "term" : { "$attribute" : "$value"} }"""
      case GreaterThan(attribute, value)        => s"""{ "range" : { "$attribute": { "gt" : "$value" } } }"""
      case GreaterThanOrEqual(attribute, value) => s"""{ "range" : { "$attribute": { "gte" : "$value" } } }"""
      case LessThan(attribute, value)           => s"""{ "range" : { "$attribute": { "lt" : "$value" } } }"""
      case LessThanOrEqual(attribute, value)    => s"""{ "range" : { "$attribute": { "lte" : "$value" } } }"""
      case In(attribute, values)                => val valuesSerialized = values.mkString("[\"", "\",\"", "\"]")
                                                   s"""{ "terms" : { "$attribute" : ["$valuesSerialized]} }"""
      case IsNull(attribute)                    => s"""{ "missing" : { "field" : "$attribute" } }"""
      case IsNotNull(attribute)                 => s"""{ "exists" : { "field" : "$attribute" } }"""
      case And(left, right)                     => s"""{ "and" : { "filters" : [ ${buildQuery(left)}, ${buildQuery(right)} ] } }"""
      case Or(left, right)                      => s"""{ "or" : { "filters" : [ ${buildQuery(left)}, ${buildQuery(right)} ] } }"""
      case Not(filterToNeg)                     => s"""{ "not" : { "filter" : ${buildQuery(filterToNeg)} } }"""
      case StringStartsWith(attribute, value)   => s"""{ "regexp": { "attribute" : "$value.*" } }"""
      case StringEndsWith(attribute, value)     => s"""{ "regexp": { "attribute" : ".*$value" } }"""
      case StringContains(attribute, value)     => s"""{ "regexp": { "attribute" : ".*$value.*" } }"""
      case _ => ""
    }
  }
}
