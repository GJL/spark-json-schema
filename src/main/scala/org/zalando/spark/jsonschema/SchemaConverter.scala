package org.zalando.spark.jsonschema

import org.apache.spark.sql.types._
import play.api.libs.json._

import scala.annotation.tailrec
import scala.io.Source

/**
 * Schema Converter for getting schema in json format into a spark Structure
 *
 * The given schema for spark has almost no validity checks, so it will make sense
 * to combine this with the schema-validator. For loading data with schema, data is converted
 * to the type given in the schema. If this is not possible the whole row will be null (!).
 * A field can be null if its type is a 2-element array, one of which is "null". The converted
 * schema doesn't check for 'enum' fields, i.e. fields which are limited to a given set.
 * It also doesn't check for required fields or if additional properties are set to true
 * or false. If a field is specified in the schema, than you can select it and it will
 * be null if missing. If a field is not in the schema, it cannot be selected even if
 * given in the dataset.
 *
 */
case class SchemaType(typeName: String, nullable: Boolean)

object SchemaConverter {

  val SchemaFieldName = "name"
  val SchemaFieldType = "type"
  val SchemaFieldId = "id"
  val SchemaStructContents = "properties"
  val SchemaArrayContents = "items"
  val SchemaRoot = "/"
  val Definitions = "definitions"
  val Reference = "$ref"
  val TypeMap = Map(
    "string" -> StringType,
    "number" -> DoubleType,
    "float" -> FloatType,
    "integer" -> LongType,
    "boolean" -> BooleanType,
    "object" -> StructType,
    "array" -> ArrayType
  )
  var definitions: JsObject = JsObject(Seq.empty)
  private var _isStrictTypingEnabled: Boolean = true

  def disableStrictTyping(): SchemaConverter.type = {
    setStrictTyping(false)
  }

  def enableStrictTyping(): SchemaConverter.type = {
    setStrictTyping(true)
  }

  private def setStrictTyping(b: Boolean) = {
    _isStrictTypingEnabled = b
    this
  }

  def convertContent(schemaContent: String): StructType = convert(parseSchemaJson(schemaContent))

  def convert(inputPath: String): StructType = convert(loadSchemaJson(inputPath))

  def convert(inputSchema: JsObject): StructType = {
    definitions = (inputSchema \ Definitions).asOpt[JsObject].getOrElse(definitions)
    val name = getJsonName(inputSchema).getOrElse(SchemaRoot)
    val typeName = getJsonType(inputSchema, name).typeName
    if (name == SchemaRoot && typeName == "object") {
      val properties = (inputSchema \ SchemaStructContents).asOpt[JsObject].getOrElse(
        throw new NoSuchElementException(
          s"Root level of schema needs to have a [$SchemaStructContents]-field"
        )
      )
      convertJsonStruct(new StructType, properties, properties.keys.toList)
    } else {
      throw new IllegalArgumentException(
        s"schema needs root level called <$SchemaRoot> and root type <object>. " +
          s"Current root is <$name> and type is <$typeName>"
      )
    }
  }

  def getJsonName(json: JsValue): Option[String] = (json \ SchemaFieldName).asOpt[String]

  def getJsonId(json: JsValue): Option[String] = (json \ SchemaFieldId).asOpt[String]

  def getJsonType(json: JsObject, name: String): SchemaType = {
    val id = getJsonId(json).getOrElse(name)

    (json \ SchemaFieldType).getOrElse(JsNull) match {
      case JsString(s) => SchemaType(s, nullable = false)
      case JsArray(array) =>
        val nullable = array.contains(JsString("null"))
        array.size match {
          case 1 if nullable =>
            throw new IllegalArgumentException("Null type only is not supported")
          case 1 =>
            SchemaType(array.apply(0).as[String], nullable = nullable)
          case 2 if nullable =>
            array.find(_ != JsString("null"))
              .map(i => SchemaType(i.as[String], nullable = nullable))
              .getOrElse {
                throw new IllegalArgumentException(
                  s"Incorrect definition of a nullable parameter at <$id>"
                )
              }
          case _ if _isStrictTypingEnabled =>
            throw new IllegalArgumentException(
              s"Unsupported type definition <${array.toString}> in schema at <$id>"
            )
          case _ => // Default to string as it is the "safest" type
            SchemaType("string", nullable = nullable)
        }
      case JsNull => throw new IllegalArgumentException(s"No <$SchemaType> in schema at <$id>")
      case t => throw new IllegalArgumentException(
        s"Unsupported type <${t.toString}> in schema at <$id>"
      )
    }
  }

  private def parseSchemaJson(schemaContent: String) = Json.parse(schemaContent).as[JsObject]

  def loadSchemaJson(filePath: String): JsObject = {
    val relPath = getClass.getResource(filePath)
    require(relPath != null, s"Path can not be reached: $filePath")
    parseSchemaJson(Source.fromURL(relPath).mkString)
  }

  @tailrec
  private def convertJsonStruct(schema: StructType, json: JsObject, jsonKeys: List[String]): StructType = {
    jsonKeys match {
      case Nil => schema
      case head :: tail =>
        val enrichedSchema = addJsonField(schema, (json \ head).as[JsObject], head)
        convertJsonStruct(enrichedSchema, json, tail)
    }
  }

  def traversePath(loc: List[String], path: JsPath): JsPath = {
    loc match {
      case head :: tail => traversePath(tail, path \ head)
      case Nil => path
    }
  }

  private def checkRefs(inputJson: JsObject): JsObject = {
    val schemaRef = (inputJson \ Reference).asOpt[JsString]
    schemaRef match {
      case Some(loc) =>
        val searchDefinitions = Definitions + "/"
        val defIndex = loc.value.indexOf(searchDefinitions) match {
          case -1 => throw new NoSuchElementException(
            s"Field with name [$Reference] requires path with [$searchDefinitions]"
          )
          case i: Int => i + searchDefinitions.length
        }
        val pathNodes = loc.value.drop(defIndex).split("/").toList
        traversePath(pathNodes, JsPath)
          .asSingleJson(definitions) match {
            case JsDefined(v) => v.as[JsObject]
            case _: JsUndefined =>
              throw new NoSuchElementException(s"Path [$loc] not found in $Definitions")
          }
      case None => inputJson
    }
  }

  private def addJsonField(schema: StructType, inputJson: JsObject, name: String): StructType = {

    val json = checkRefs(inputJson)
    val (dataType, nullable) = getFieldType(json, name)

    schema.add(getJsonName(json).getOrElse(name), dataType, nullable = nullable)
  }

  private def getFieldType(json: JsObject, name: String): (DataType, Boolean) = {
    val fieldType = getJsonType(json, name)
    TypeMap(fieldType.typeName) match {

      case dataType: DataType =>
        (dataType, fieldType.nullable)

      case ArrayType =>
        val innerJson = checkRefs((json \ SchemaArrayContents).as[JsObject])
        val innerJsonType = getFieldType(innerJson, "")
        val dataType = ArrayType(innerJsonType._1, innerJsonType._2)
        (dataType, getJsonType(json, name).nullable)

      case StructType =>
        val dataType = getDataType(json, JsPath \ SchemaStructContents)
        (dataType, getJsonType(json, name).nullable)
    }
  }

  private def getDataType(inputJson: JsObject, contentPath: JsPath): DataType = {
    val json = checkRefs(inputJson)

    val content = contentPath.asSingleJson(json) match {
      case JsDefined(v) => v.as[JsObject]
      case _: JsUndefined => JsObject(Seq.empty)
    }

    convertJsonStruct(new StructType, content, content.keys.toList)
  }
}
