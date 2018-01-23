package gestalt

import java.util.UUID

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import play.api.Logger
import play.api.libs.json._
import play.api.libs.ws.{WSAuthScheme, WSClient, WSRequest}
import play.api.libs.ws.ahc.AhcWSClient
import play.api.libs.json._
import play.api.libs.json.Reads._
import play.api.libs.functional.syntax._

import scala.collection.mutable
import scala.util.{Failure, Success, Try}
import scala.concurrent.Await
import scala.concurrent.duration._

class MetaSchemaDiff {

  val logger = Logger(this.getClass)

  val baseMetaUrl = getEnv("META_URL")
  val apiKey      = getEnv("API_KEY")
  val apiSecret   = getEnv("API_SECRET")
  val targetFqon      = getEnv("TARGET_FQON")
  val targetWorkspace = getEnv("TARGET_WORKSPACE")
  val targetProvider  = getEnv("TARGET_PROVIDER")
  val securityDockerImage = getEnv("SECURITY_IMAGE", Some("galacticfog/gestalt-security:release-1.5.0"))
  val testMetaUrl         = getEnv("TEST_META_URL", Some(baseMetaUrl))
  val testMetaApiKey      = getEnv("TEST_API_KEY", Some(apiKey))
  val testMetaApiSec      = getEnv("TEST_API_SECRET", Some(apiSecret))
  val slackUrl            = getEnv("SLACK_URL")

  implicit val system = ActorSystem()
  implicit val materializer = ActorMaterializer()
  val ws: WSClient = AhcWSClient()

  def log(m: String) = logger.info(m)

  private[this] implicit def baseMetaRequestBuilder(endpoint: String) = ws.url(baseMetaUrl + "/" + endpoint.stripPrefix("/")).withAuth(apiKey, apiSecret, WSAuthScheme.BASIC)

  private[this] def testMeta(endpoint: String) = ws.url(baseMetaUrl + "/" + endpoint.stripPrefix("/")).withAuth(apiKey, apiSecret, WSAuthScheme.BASIC)

  private[this] def rawRequestBuilder(url: String): WSRequest = ws.url(url)


  def diff(payloadStr: String, ctxStr: String): String = {

    val payload = Try{Json.parse(payloadStr)} getOrElse Json.obj()
    log(s"parsed payload is: '${payload}'")
    val ctx = Json.parse(ctxStr)
    log(s"parsed context is: '${ctx}'")

    val workspace = REST(s"/${targetFqon}/workspaces").get.as[Seq[JsObject]].find(js => (js \ "name").as[String] == targetWorkspace) getOrElse {throw new RuntimeException(s"could not find target workspace: ${targetWorkspace}")}
    val workspaceId = (workspace \ "id").as[String]

    log("creating test environment")
    val testEnvIdTry = REST(s"/${targetFqon}/workspaces/${workspaceId}/environments", "POST", Some(Json.obj(
      "name" -> UUID.randomUUID().toString.substring(0,7),
      "properties" -> Json.obj(
        "environment_type" -> "test"
      )
    ))) flatMap {js => Try{(js \ "id").as[String]}}

    val metaDockerImageTry = REST("/about").flatMap(j => Try{(j \ "docker_image").as[String]})

    val diff = Try {
      val Success(goldMeta) = for {
        testEnvId <- testEnvIdTry
        metaDockerImage <- metaDockerImageTry
        _ = log(s"created test environment ${testEnvId}")
        //// Create containers first
        // database
        dbContainer <- {
          log("creating postgres container")
          REST(s"/$targetFqon/environments/$testEnvId/containers", "POST", Some(dbContainer))
        }
        dbContainerAddress <- Try {
          (dbContainer \ "properties" \ "port_mappings" \ (0) \ "service_address" \ "host").as[String]
        }
        // security
        securityContainer <- {
          log("creating security container")
          REST(s"/$targetFqon/environments/$testEnvId/containers", "POST", Some(securityContainer(dbContainerAddress)))
        }
        testSecurityAddress <- Try {
          (securityContainer \ "properties" \ "port_mappings" \ (0) \ "service_address" \ "host").as[String]
        }
        // meta
        metaContainer <- {
          log("creating meta container")
          REST(s"/$targetFqon/environments/$testEnvId/containers", "POST", Some(metaContainer(testSecurityAddress, dbContainerAddress, metaDockerImage)))
        }
        goldMetaAddress <- Try {
          (metaContainer \ "properties" \ "port_mappings" \ (0) \ "service_address" \ "host").as[String]
        }
        //// init security
        _ <- retry(20, 3 seconds){ i =>
          log(s"Attempt $i to check security /init")
          val ready = REST(s"http://$testSecurityAddress:9455/init")(rawRequestBuilder)
          log(ready.toString)
          ready.flatMap(js => Try { (js \ "initialized").as[Boolean] == false })
        }
        initSecurity <- REST(s"http://$testSecurityAddress:9455/init", "POST", Some(Json.obj(
          "username" -> "admin", "password" -> "admin"
        )))(rawRequestBuilder)
        testApiKey <- Try { (initSecurity \ (0) \ "apiKey").as[String] }
        testApiSecret <- Try { (initSecurity \ (0) \ "apiSecret").as[String] }
        _ = log(s"security credentials: $testApiKey:$testApiSecret")
        //// bootstrap meta
        goldMetaUrl = s"http://$goldMetaAddress:14374"
        goldMeta = (endpoint: String) => ws.url(goldMetaUrl + "/" + endpoint.stripPrefix("/")).withAuth(testApiKey, testApiSecret, WSAuthScheme.BASIC)
      } yield goldMeta

      val Success(bootstrapMeta) = for {
        _ <- retry(20, 3 seconds){ i =>
          log(s"Attempt $i to check meta /about")
          val ready = REST("/about")(goldMeta)
          log(ready.toString)
          ready.flatMap(js => Try { (js \ "status").as[String] == "OK" })
        }
        _ <- REST("/bootstrap", "POST", None, timeout = 30)(goldMeta)
      } yield ()

      val testRTs = REST("/root/resourcetypes?expand=true&withprops=true")(testMeta)
        .flatMap(js => Try{js.as[Seq[ResourceType]]})
        .getOrElse(throw new RuntimeException("could not retrieve schema from test meta"))
        .map(rt => (rt.id,rt)).toMap

      val goldRTs = REST("/root/resourcetypes?expand=true&withprops=true")(goldMeta)
        .flatMap(js => Try{js.as[Seq[ResourceType]]})
        .getOrElse(throw new RuntimeException("could not retrieve schema from gold meta"))
        .map(rt => (rt.id,rt)).toMap

      computeDiff(testRTs, goldRTs)
    }

    val slackMsg = generateSlackMessage(metaDockerImageTry, diff)
    REST(slackUrl, "POST", Some(slackMsg))(rawRequestBuilder) match {
      case Success(_)   => log("posted message to slack")
      case Failure(err) => log(s"error posting message to slack:\n${err.toString}")
    }

    testEnvIdTry foreach {
      envId =>
        log("cleaning up by deleting test environment")
        val delete = REST(s"/${targetFqon}/environments/${envId}?force=true", "DELETE", None, timeout = 10)
        log(s"deleted environment ${envId}: ${delete.toString}")
    }

    "done"
  }


  case class DiffResult(missingTypes: Iterable[(UUID,String)],
                        inequalTypes: Iterable[(ResourceType,ResourceType)],
                        missingProperties: Iterable[String],
                        badProperties: Iterable[PropertyDefinition],
                        extraProperties: Iterable[String] )

  private[this] def computeDiff(testRTs: Map[UUID, ResourceType], goldRTs: Map[UUID, ResourceType]): DiffResult = {
    val missingrts = goldRTs.map(rt => (rt._1,rt._2.name)).toSet diff testRTs.map(rt => (rt._1,rt._2.name)).toSet
    missingrts.foreach {
      case (id,name) =>
        val msg = s"missing ResourceType $name with id $id"
        log(msg)
    }

    val deepCheckIds = goldRTs.map(_._1).toSet intersect testRTs.map(_._1).toSet
    log(s"resource types to deep check: ${deepCheckIds}")
    val inequalTypes = for {
      id <- deepCheckIds
      testrt = testRTs(id).copy(property_defs = Seq.empty)
      goldrt = goldRTs(id).copy(property_defs = Seq.empty)
      if testrt != goldrt
    } yield (testrt,goldrt)
    log(s"inequal resource types: ${inequalTypes.map(_._1.name).mkString(", ")}")

    log("checking property defs")

    val extraprops = (for {
      id <- deepCheckIds
      testpdefs = testRTs(id).property_defs
      goldpdefs = goldRTs(id).property_defs
      extra = (testpdefs.map(_.name).toSet diff goldpdefs.map(_.name).toSet) map (s"${testRTs(id).name} has extra property definition: " + _)
    } yield extra) flatten

    extraprops foreach log

    val missingprops = (for {
      id <- deepCheckIds
      testpdefs = testRTs(id).property_defs
      goldpdefs = goldRTs(id).property_defs
      missing = (goldpdefs.map(_.name).toSet diff testpdefs.map(_.name).toSet) map (s"${goldRTs(id).name} is missing property definition: " + _)
    } yield missing) flatten

    missingprops foreach log

    val badprops = (for {
      id <- deepCheckIds
      dummyid = UUID.randomUUID()
      testpdefs = testRTs(id).property_defs.map(_.copy(id = dummyid))
      goldpdefs = goldRTs(id).property_defs.map(_.copy(id = dummyid))
      bad = Set.empty[PropertyDefinition] // FINISH ME
    } yield bad) flatten

    DiffResult(
      missingTypes = missingrts,
      inequalTypes = inequalTypes,
      missingProperties = missingprops,
      badProperties = badprops,
      extraProperties = extraprops
    )
  }

  private[this] def retry(numTries: Int, delay: Duration)(f: Int => Try[Boolean]): Try[Boolean] = {
    var i = 0
    var t: Try[Boolean] = Failure(new RuntimeException)
    while (i < numTries && t.isFailure ) {
      i += 1
      t = f(i)
      if (t.isFailure) Thread.sleep(delay.toMillis)
    }
    t.flatMap {
      case true  => Success(true)
      case false => Failure(new RuntimeException("unexpected return"))
    }
  }

  private[this] def generateSlackMessage(metaDockerImageTry: Try[String], diff: Try[DiffResult]): JsObject = {
    val header = Json.obj(
      "fields" -> Seq(Json.obj(
        "title" -> "Target",
        "value" -> testMetaUrl
      ), Json.obj(
        "title" -> "Image",
        "value" -> metaDockerImageTry.getOrElse[String]("error retrieving meta info")
      ))
    )
    def pretext(s: String): JsObject = Json.obj("pretext" -> s)
    def attachment(color: String, text: String) = Json.obj("color" -> color, "text" -> text)
    def green(text: String) = attachment("#00ff00", text)
    def red(text: String) = attachment("#ff0000", text)
    def yellow(text: String) = attachment("#ffff33", text)

    diff match {
      case Success(DiffResult(missingTypes, inequalTypes, missingProperties, badProperties, extraProperties)) =>
        val missing = pretext("Missing ResourceTypes") ++ {
          if (missingTypes.isEmpty) green("All factory ResourceTypes were present :100:")
          else red(missingTypes.map("missing ResourceType " + _._2).mkString("\n"))
        }
        val inequal = pretext("Incorrect ResourceType properties") ++ {
          if (inequalTypes.isEmpty) green("All factory ResourceTypes had correct properties :100:")
          else red(inequalTypes.map(ResourceType.diff).mkString("\n"))
        }
        val extra = pretext("Additional property definitions in test") ++ {
          if (extraProperties.isEmpty) green("No additional properties.")
          else yellow(extraProperties.mkString("\n"))
        }
        val missingprops = pretext("Missing PropertyDefinitions") ++ {
          if (missingProperties.isEmpty) green("All factory PropertyDefinitions were present :100:")
          else red(missingProperties.mkString("\n"))
        }
        Json.obj(
          "username" -> "meta-schema-diff-lambda",
          "attachments" -> Seq( header, missing, inequal, missingprops, extra )
        )
      case Failure(err) =>
        val error = Json.obj(
          "color" -> "#ff0000",
          "pretext" -> s"Error during lambda",
          "text" -> err.toString
        )
        Json.obj(
          "username" -> "meta-schema-diff-lambda",
          "attachments" -> Seq( header, error )
        )
    }
  }

  private[this] def getEnv(name: String, default: Option[String] = None): String = {
    sys.env.get(name) orElse default getOrElse {throw new RuntimeException(s"could not location environment variable '${name}'")}
  }

  private[this] def REST(endpoint: String, method: String = "GET", payload: Option[JsValue] = None, timeout: Int = 5)
                        (implicit requestBuilder: (String => WSRequest)): Try[JsValue] = {
    val fr = payload.foldLeft(
      requestBuilder(endpoint)
    )({case (r,b) => r.withBody(b)}).execute(method)
    for {
      resp <- Try{Await.result(fr, timeout seconds)}
      ret <- resp.status match {
        case 204 => Success(Json.obj())
        case ok if 200 <= ok && ok <= 299 => Try(resp.json) orElse Success(JsString(resp.body))
        case _ => Failure(
          new RuntimeException(Try{ (resp.json \ "message").as[String] } getOrElse resp.body)
        )
      }
    } yield ret
  }

  private[this] def metaContainer(testSecurityAddress: String, dbContainerAddress: String, metaDockerImage: String): JsObject = Json.obj(
    "name" -> "meta",
    "properties" -> Json.obj(
      "container_type" -> "DOCKER",
      "cpus" -> 1,
      "env" -> Json.obj(
        "GESTALT_SECURITY_HOSTNAME" -> testSecurityAddress,
        "GESTALT_SECURITY_PROTOCOL" -> "http",
        "GESTALT_SECURITY_PORT" -> "9455",
        "DATABASE_HOSTNAME" -> dbContainerAddress,
        "DATABASE_PORT" -> "5432",
        "DATABASE_NAME" -> "gestalt-meta",
        "DATABASE_PASSWORD" -> "gestaltdev",
        "DATABASE_USERNAME" -> "gestaltdev",
        "RABBIT_HTTP_PORT" -> "15672",
        "RABBIT_HOST" -> "dummy-rabbit",
        "RABBIT_PORT" -> "5672",
        "RABBIT_ROUTE" -> "dummy-rabbit-route",
        "RABBIT_EXCHANGE" -> "dummy-rabbit-exchange"
      ),
      "force_pull" -> true,
      "image" -> metaDockerImage,
      "memory" -> 2048,
      "network" -> "BRIDGE",
      "num_instances" -> 1,
      "port_mappings" -> Seq(Json.obj(
        "container_port" -> 9000,
        "expose_endpoint" -> true,
        "name" -> "meta",
        "protocol" -> "tcp",
        "service_port" -> 14374
      )),
      "provider" -> Json.obj(
        "id" -> targetProvider
      )
    )
  )

  private[this] def securityContainer(dbContainerAddress: String): JsObject = Json.obj(
    "name" -> "security",
    "properties" -> Json.obj(
      "container_type" -> "DOCKER",
      "cpus" -> 1,
      "env" -> Json.obj(
        "DATABASE_HOSTNAME" -> dbContainerAddress,
        "DATABASE_PORT" -> "5432",
        "DATABASE_NAME" -> "gestalt-security",
        "DATABASE_PASSWORD" -> "gestaltdev",
        "DATABASE_USERNAME" -> "gestaltdev"
      ),
      "force_pull" -> true,
      "image" -> securityDockerImage,
      "memory" -> 2048,
      "network" -> "BRIDGE",
      "num_instances" -> 1,
      "port_mappings" -> Seq(Json.obj(
        "container_port" -> 9000,
        "expose_endpoint" -> true,
        "name" -> "security",
        "protocol" -> "tcp",
        "service_port" -> 9455
      )),
      "provider" -> Json.obj(
        "id" -> targetProvider
      )
    )
  )

  private[this] def dbContainer: JsObject = Json.obj(
    "name" -> "postgres",
    "properties" -> Json.obj(
      "container_type" -> "DOCKER",
      "cpus" -> 1,
      "env" -> Json.obj(
        "POSTGRES_PASSWORD" -> "gestaltdev",
        "POSTGRES_USER" -> "gestaltdev"
      ),
      "force_pull" -> true,
      "image" -> "galacticfog/gestalt-data:release-1.2.0",
      "memory" -> 1024,
      "network" -> "BRIDGE",
      "num_instances" -> 1,
      "port_mappings" -> Seq(Json.obj(
        "container_port" -> 5432,
        "expose_endpoint" -> true,
        "name" -> "sql",
        "protocol" -> "tcp",
        "service_port" -> 5432
      )),
      "provider" -> Json.obj(
        "id" -> targetProvider
      )
    )
  )

  case class ResourceLink( id: UUID, name: String )

  case class LineageInfo( child_types: Seq[UUID], parent_types: Seq[UUID] )

  case class ActionInfo( prefix: String, verbs: Seq[String] )

  case object ResourceType {
    case class Properties(`abstract`: Option[Boolean] = None,
                          actions: Option[ActionInfo] = None,
                          lineage: Option[LineageInfo] = None)

    implicit val lineageInfoFmt = Json.format[LineageInfo]
    implicit val actionInfoFmt = Json.format[ActionInfo]
    implicit val propertiesFmt = Json.format[Properties]

    def diff(rts: (ResourceType, ResourceType)): String = {
      val a = rts._1
      val b = rts._2
      a.name + " differ: " + Seq(
        if (a.properties.`abstract` != b.properties.`abstract`) Some("abstract") else None,
        if (a.properties.actions.map(_.prefix) != b.properties.actions.map(_.prefix)) Some("actions.prefix") else None,
        if (a.properties.actions.map(_.verbs.sorted) != b.properties.actions.map(_.verbs.sorted)) Some("actions.verbs") else None,
        if (a.properties.lineage.map(_.child_types.sorted) != b.properties.lineage.map(_.child_types.sorted)) Some("lineage.child_types") else None,
        if (a.properties.lineage.map(_.parent_types.sorted) != b.properties.lineage.map(_.parent_types.sorted)) Some("lineage.parent_types") else None
      ).flatten.mkString(", ")
    }
  }

  case class PropertyDefinition( id: UUID,
                                 name: String,
                                 applies_to: String,
                                 data_type: String,
                                 is_sealed: Option[Boolean],
                                 is_system: Option[Boolean],
                                 requirement_type: Option[String],
                                 visibility_type: Option[String] )

  case object PropertyDefinition {
    implicit val propDefFmt = Json.format[PropertyDefinition]
  }

  case class ResourceType(id: UUID,
                          name: String,
                          properties: ResourceType.Properties,
                          property_defs: Seq[PropertyDefinition] )

  implicit val resourceLinkFmt = Json.format[ResourceLink]

  implicit val resourceTypeRds: Reads[ResourceType] = (
    (__ \ "id").read[UUID] and
      (__ \ "name").read[String] and
      ((__ \ "properties").read[ResourceType.Properties] or Reads.pure(ResourceType.Properties())) and
      ((__ \ "property_defs").read[Seq[PropertyDefinition]] or Reads.pure(Seq.empty[PropertyDefinition]))
  )(ResourceType.apply _)

}
