/*
 * Copyright [2017] [B2W Digital]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.marvin.executor.api

import java.io.FileNotFoundException
import java.util.concurrent.Executors

import actions.HealthCheckResponse.Status
import akka.actor.{ActorRef, ActorSystem, Props, Terminated}
import akka.event.{Logging, LoggingAdapter}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.pattern.ask
import akka.util.Timeout
import com.github.fge.jsonschema.core.exceptions.ProcessingException
import com.typesafe.config.{Config, ConfigFactory}
import org.marvin.executor.actions.BatchAction.{BatchExecute, BatchHealthCheck, BatchReload}
import org.marvin.executor.actions.OnlineAction.{OnlineExecute, OnlineHealthCheck}
import org.marvin.executor.actions.PipelineAction.PipelineExecute
import org.marvin.executor.actions.{BatchAction, OnlineAction, PipelineAction}
import org.marvin.executor.api.GenericHttpAPI.{complete, healthStatusFormat}
import org.marvin.executor.api.exception.EngineExceptionAndRejectionHandler._
import org.marvin.executor.api.model.HealthStatus
import org.marvin.executor.manager.ExecutorManager
import org.marvin.executor.statemachine.{PredictorFSM, Reload}
import org.marvin.model.{EngineMetadata, MarvinEExecutorException}
import org.marvin.util.{ConfigurationContext, JsonUtil, ProtocolUtil}
import spray.json.DefaultJsonProtocol._

import scala.concurrent._
import scala.concurrent.duration._
import scala.io.Source
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

case class HttpEngineResponse(result: String)
case class HttpEngineRequest(params: Option[String] = Option.empty, message: Option[String] = Option.empty)

class GenericHttpAPIImpl() extends GenericHttpAPI

object GenericHttpAPI extends HttpMarvinApp {
  var system: ActorSystem = _
  var config: Config = _

  var defaultParams: String = _
  var metadata: EngineMetadata = _
  var log: LoggingAdapter = _

  var api: GenericHttpAPI = new GenericHttpAPIImpl()
  var protocolUtil: ProtocolUtil = _

  var actors:Map[String, ActorRef] = _
  var predictorFSM: ActorRef = _
  var acquisitorActor: ActorRef = _
  var tpreparatorActor: ActorRef = _
  var trainerActor: ActorRef = _
  var evaluatorActor: ActorRef = _
  var pipelineActor: ActorRef = _
  var feedbackActor: ActorRef = _
  var executorManager: ActorRef = _

  var onlineActionTimeout:Timeout = _
  var healthCheckTimeout:Timeout = _
  var batchActionTimeout:Timeout = _
  var reloadTimeout:Timeout = _
  var pipelineTimeout:Timeout = _

  implicit val httpEngineResponseFormat = jsonFormat1(HttpEngineResponse)
  implicit val httpEngineRequestFormat = jsonFormat2(HttpEngineRequest)
  implicit val healthStatusFormat = jsonFormat2(HealthStatus)

  override def routes: Route = handleRejections(marvinEngineRejectionHandler){
      handleExceptions(marvinEngineExceptionHandler){
        post {
          path("predictor") {
            entity(as[HttpEngineRequest]) { request =>
              require(!request.message.isEmpty, "The request payload must contain the attribute 'message'.")
              val response_message = api.onlineExecute("predictor", request.params.getOrElse(defaultParams), request.message.get)
              onComplete(api.toHttpEngineResponseFuture(response_message)){ response =>
                response match{
                  case Success(httpEngineResponse) => complete(httpEngineResponse)
                  case Failure(e) => {
                    log.info("RECEIVE FAILURE!!! "+e.getMessage + e.getClass)
                    throw e
                  }
                }
              }
            }
          } ~
          path("acquisitor") {
            entity(as[HttpEngineRequest]) { request =>
              complete {
                val response_message = api.batchExecute("acquisitor", request.params.getOrElse(defaultParams))
                api.toHttpEngineResponse(response_message)
              }
            }
          } ~
          path("tpreparator") {
            entity(as[HttpEngineRequest]) { request =>
              complete {
                val response_message = api.batchExecute("tpreparator", request.params.getOrElse(defaultParams))
                api.toHttpEngineResponse(response_message)
              }
            }
          } ~
          path("trainer") {
            entity(as[HttpEngineRequest]) { request =>
              complete {
                val response_message = api.batchExecute("trainer", request.params.getOrElse(defaultParams))
                api.toHttpEngineResponse(response_message)
              }
            }
          } ~
          path("evaluator") {
            entity(as[HttpEngineRequest]) { request =>
              complete {
                val response_message = api.batchExecute("evaluator", request.params.getOrElse(defaultParams))
                api.toHttpEngineResponse(response_message)
              }
            }
          } ~
          path("pipeline") {
            entity(as[HttpEngineRequest]) { request =>
              complete {
                val response_message = api.pipeline(request.params.getOrElse(defaultParams))
                api.toHttpEngineResponse(response_message)
              }
            }
          } ~
          path("feedback") {
            entity(as[HttpEngineRequest]) { request =>
              require(!request.message.isEmpty, "The request payload must contain the attribute 'message'.")
              val response_message = api.onlineExecute("feedback", request.params.getOrElse(defaultParams), request.message.get)
              onComplete(api.toHttpEngineResponseFuture(response_message)){ response =>
                response match{
                  case Success(httpEngineResponse) => complete(httpEngineResponse)
                  case Failure(e) => {
                    log.info("RECEIVE FAILURE!!! "+e.getMessage + e.getClass)
                    throw e
                  }
                }
              }
            }
          }
        } ~
        put {
          path("predictor" / "reload") {
            parameters('protocol) { (protocol) =>
              complete {
                val response_message = api.reload("predictor", "online", protocol=protocol)
                api.toHttpEngineResponse(response_message)
              }
            }
          } ~
          path("tpreparator" / "reload") {
            parameters('protocol) { (protocol) =>
              complete {
                val response_message = api.reload("tpreparator", "batch", protocol=protocol)
                api.toHttpEngineResponse(response_message)
              }
            }
          } ~
          path("trainer" / "reload") {
            parameters('protocol) { (protocol) =>
              complete {
                val response_message = api.reload("trainer", "batch", protocol=protocol)
                api.toHttpEngineResponse(response_message)
              }
            }
          } ~
          path("evaluator" / "reload") {
            parameters('protocol) { (protocol) =>
              complete {
                val response_message = api.reload("evaluator", "batch", protocol=protocol)
                api.toHttpEngineResponse(response_message)
              }
            }
          } ~
          path("feedback" / "reload") {
            parameters('protocol) { (protocol) =>
              complete {
                val response_message = api.reload("feedback", "online", protocol=protocol)
                api.toHttpEngineResponse(response_message)
              }
            }
          }
        } ~
        get {
          path("predictor" / "health") {
            onComplete(api.check("predictor", "online")) { response =>
              api.matchHealthTry(response)
            }
          } ~
          path("acquisitor" / "health") {
            onComplete(api.check("acquisitor", "batch")) { response =>
              api.matchHealthTry(response)
            }
          } ~
          path("tpreparator" / "health") {
            onComplete(api.check("tpreparator", "batch")) { response =>
              api.matchHealthTry(response)
            }
          } ~
          path("trainer" / "health") {
            onComplete(api.check("trainer", "batch")) { response =>
              api.matchHealthTry(response)
            }
          } ~
          path("evaluator" / "health") {
            onComplete(api.check("evaluator", "batch")) { response =>
              api.matchHealthTry(response)
            }
          } ~
          path("feedback" / "health") {
            onComplete(api.check("feedback", "online")) { response =>
              api.matchHealthTry(response)
            }
          }
        }
      }
    }

  def main(args: Array[String]): Unit = {

    //Get all VM options
    val engineFilePath = s"${ConfigurationContext.getStringConfigOrDefault("engineHome", ".")}/engine.metadata"
    val paramsFilePath = s"${ConfigurationContext.getStringConfigOrDefault("engineHome", ".")}/engine.params"

    val ipAddress = ConfigurationContext.getStringConfigOrDefault("ipAddress", "localhost")
    val port = ConfigurationContext.getIntConfigOrDefault("port", 8000)

    val modelProtocolToLoad = ConfigurationContext.getStringConfigOrDefault("modelProtocol", "")

    val enableAdmin = ConfigurationContext.getBooleanConfigOrDefault("enableAdmin", false)
    val adminPort = ConfigurationContext.getIntConfigOrDefault("adminPort", 50100)
    val adminHost = ConfigurationContext.getStringConfigOrDefault("adminHost", "127.0.0.1")

    //Load engine files
    val metadata = api.readJsonIfFileExists[EngineMetadata](engineFilePath, true)
    val defaultParams = JsonUtil.toJson(api.readJsonIfFileExists[Map[String, String]](paramsFilePath))

    //setup custom configuration for actor system
    api.setupConfig(enableAdmin, adminHost, adminPort)

    //setup actors system before start server
    api.setupSystem(metadata, defaultParams, modelProtocolToLoad, enableAdmin)

    //start http server
    api.startServer(ipAddress, port)
  }
}

trait GenericHttpAPI {
  def setupSystem(metadata: EngineMetadata, defaultParams: String, modelProtocol:String, enableAdmin:Boolean): ActorSystem = {

    val system = ActorSystem(metadata.name, GenericHttpAPI.config)

    GenericHttpAPI.system = system
    GenericHttpAPI.metadata = metadata
    GenericHttpAPI.protocolUtil = new ProtocolUtil()
    GenericHttpAPI.defaultParams = defaultParams
    GenericHttpAPI.log = Logging.getLogger(system, this)

    GenericHttpAPI.onlineActionTimeout = Timeout(metadata.onlineActionTimeout millisecond)
    GenericHttpAPI.healthCheckTimeout = Timeout(metadata.healthCheckTimeout millisecond)
    GenericHttpAPI.batchActionTimeout = Timeout(metadata.batchActionTimeout millisecond)
    GenericHttpAPI.reloadTimeout = Timeout(metadata.reloadTimeout millisecond)

    val totalPipelineTimeout = (metadata.reloadTimeout + metadata.batchActionTimeout) * metadata.pipelineActions.length * 1.20
    GenericHttpAPI.pipelineTimeout = Timeout(totalPipelineTimeout milliseconds)

    GenericHttpAPI.acquisitorActor = system.actorOf(Props(new BatchAction("acquisitor", metadata)), name = "acquisitorActor")
    GenericHttpAPI.tpreparatorActor = system.actorOf(Props(new BatchAction("tpreparator", metadata)), name = "tpreparatorActor")
    GenericHttpAPI.trainerActor = system.actorOf(Props(new BatchAction("trainer", metadata)), name = "trainerActor")
    GenericHttpAPI.evaluatorActor = system.actorOf(Props(new BatchAction("evaluator", metadata)), name = "evaluatorActor")
    GenericHttpAPI.pipelineActor = system.actorOf(Props(new PipelineAction(metadata)), name = "pipelineActor")
    GenericHttpAPI.predictorFSM = system.actorOf(Props(new PredictorFSM(metadata)), name = "predictorFSM")
    GenericHttpAPI.feedbackActor = system.actorOf(Props(new OnlineAction("feedback", metadata)), name = "feedbackActor")

    GenericHttpAPI.actors = Map[String, ActorRef](
      "predictor" -> GenericHttpAPI.predictorFSM,
      "acquisitor" -> GenericHttpAPI.acquisitorActor,
      "tpreparator" -> GenericHttpAPI.tpreparatorActor,
      "trainer" -> GenericHttpAPI.trainerActor,
      "evaluator" -> GenericHttpAPI.evaluatorActor,
      "pipeline" -> GenericHttpAPI.pipelineActor,
      "feedback" -> GenericHttpAPI.feedbackActor
    )

    if (enableAdmin){
      GenericHttpAPI.executorManager = system.actorOf(Props(new ExecutorManager(metadata, GenericHttpAPI.actors)), name="executorManager")
    }

    //send model protocol to be reloaded by predictor service
    GenericHttpAPI.predictorFSM ! Reload(modelProtocol)

    system
  }

  def setupConfig(enableAdmin:Boolean, adminHost:String, adminPort:Int) = {
    if (enableAdmin) {

      val configuration = """
        akka{
          actor {
            provider = remote
          }

          remote.artery {
            enabled = on
            canonical.hostname = "{hostname}"
            canonical.port = {port}
          }
        }
      """.replace("{hostname}", adminHost).replace("{port}", adminPort.toString)

      //set the new configuration
      GenericHttpAPI.config = ConfigFactory.parseString(configuration).withFallback(ConfigFactory.load())

    } else {
      //set the default configuration (from appication.conf file)
      GenericHttpAPI.config = ConfigFactory.load()
    }

    GenericHttpAPI.config
  }

  def startServer(ipAddress: String, port: Int): Unit = {
    scala.sys.addShutdownHook{GenericHttpAPI.system.terminate()}
    GenericHttpAPI.startServer(ipAddress, port, GenericHttpAPI.system)
  }

  def terminate(): Future[Terminated] = {
    GenericHttpAPI.system.terminate()
  }

  def asHealthStatus: PartialFunction[Status, HealthStatus] = new PartialFunction[Status, HealthStatus] {
    override def apply(status: Status): HealthStatus = {
      val statusTyped = status.asInstanceOf[Status]
      if(statusTyped.isOk){
        HealthStatus(status = "OK", additionalMessage = "")
      } else {
        HealthStatus(status = "NOK", additionalMessage = "Engine did not returned a healthy status.")
      }
    }
    override def isDefinedAt(status: Status): Boolean = status != null
  }

  def toHttpEngineResponse(message:String): HttpEngineResponse = {
    HttpEngineResponse(result = message)
  }

  def toHttpEngineResponseFuture(message:Future[String]): Future[HttpEngineResponse] = {
    implicit val ec: ExecutionContext = GenericHttpAPI.system.dispatcher
    message collect { case response => HttpEngineResponse(result = response)}
  }

  def readJsonIfFileExists[T: ClassTag](filePath: String, validate: Boolean = false): T = {
    Try(JsonUtil.fromJson[T](Source.fromFile(filePath).mkString, validate)) match {
      case Success(json) => json
      case Failure(ex) => {
        ex match {
          case ex: FileNotFoundException => throw new MarvinEExecutorException(s"The file [$filePath] does not exists." +
            s" Check your engine configuration.", ex)
          case ex: ProcessingException => throw new MarvinEExecutorException(s"Invalid engine metadata file."  +
            s" Check your engine metadata file.", ex)
          case _ => throw ex
        }
      }
    }
  }

  def matchHealthTry(response: Try[HealthStatus]) = response match {
    case Success(healthStatus) =>
      if(healthStatus.status.equals("OK"))
        complete(healthStatus)
      else
        complete(HttpResponse(StatusCodes.ServiceUnavailable, entity = HttpEntity(ContentTypes.`application/json`, healthStatusFormat.write(healthStatus).toString())))

    case Failure(e) => throw e
  }

  def batchExecute(actionName: String, params: String): String = {
    GenericHttpAPI.log.info(s"Request for $actionName] received.")
    implicit val ec = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())
    implicit val futureTimeout = GenericHttpAPI.batchActionTimeout
    val protocol = GenericHttpAPI.protocolUtil.generateProtocol(actionName)
    GenericHttpAPI.actors(actionName) ! BatchExecute(protocol, params)
    protocol
  }

  def onlineExecute(actionName: String, params: String, message: String): Future[String] = {
    GenericHttpAPI.log.info(s"Request for $actionName] received.")
    implicit val ec = GenericHttpAPI.system.dispatchers.lookup("marvin-online-dispatcher")
    implicit val futureTimeout = GenericHttpAPI.onlineActionTimeout
    (GenericHttpAPI.actors(actionName) ? OnlineExecute(message, params)).mapTo[String]
  }

  def reload(actionName: String, actionType:String, protocol: String): String = {
    implicit val ec = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())
    implicit val futureTimeout = GenericHttpAPI.reloadTimeout

    actionType match {
      case "online" =>
        GenericHttpAPI.actors(actionName) ! Reload(protocol)

      case "batch" =>
        GenericHttpAPI.actors(actionName) ! BatchReload(protocol)
    }

    "Work in progress...Thank you folk!"
  }

  def check(actionName: String, actionType:String): Future[HealthStatus] = {
    implicit val ec = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())
    implicit val futureTimeout = GenericHttpAPI.healthCheckTimeout

    actionType match {
      case "online" =>
        (GenericHttpAPI.actors(actionName) ? OnlineHealthCheck).mapTo[Status] collect asHealthStatus

      case "batch" =>
        (GenericHttpAPI.actors(actionName) ? BatchHealthCheck).mapTo[Status] collect asHealthStatus
    }
  }

  def pipeline(params: String): String = {
    GenericHttpAPI.log.info(s"Request pipeline process received.")
    implicit val ec = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())
    implicit val futureTimeout = GenericHttpAPI.pipelineTimeout
    val protocol = GenericHttpAPI.protocolUtil.generateProtocol("pipeline")
    GenericHttpAPI.actors("pipeline") ! PipelineExecute(protocol, params)
    protocol
  }

}