package org.jetbrains.ktor.features

import org.jetbrains.ktor.application.*
import org.jetbrains.ktor.http.*
import org.jetbrains.ktor.pipeline.*
import org.jetbrains.ktor.request.*
import org.jetbrains.ktor.util.*
import org.slf4j.*
import org.slf4j.event.*

class CallLogging(private val log: Logger, private val monitor: ApplicationMonitor, private val level: Level) {
    class Configuration {
        var level: Level = Level.TRACE
    }

    private val starting: (Application) -> Unit = { log("Application starting: $it") }
    private val started: (Application) -> Unit = { log("Application started: $it") }
    private val stopping: (Application) -> Unit = { log("Application stopping: $it") }
    private var stopped: (Application) -> Unit = {}

    init {
        stopped = {
            log("Application stopped: $it")
            monitor.applicationStarting -= starting
            monitor.applicationStarted -= started
            monitor.applicationStopping -= stopping
            monitor.applicationStopped -= stopped
        }

        monitor.applicationStarting += starting
        monitor.applicationStarted += started
        monitor.applicationStopping += stopping
        monitor.applicationStopped += stopped
    }

    companion object Feature : ApplicationFeature<Application, Configuration, CallLogging> {
        override val key: AttributeKey<CallLogging> = AttributeKey("Call Logging")
        override fun install(pipeline: Application, configure: Configuration.() -> Unit): CallLogging {
            val loggingPhase = PipelinePhase("Logging")
            val configuration = Configuration().apply(configure)
            val level = when {
                configuration.level == Level.TRACE && pipeline.log.isTraceEnabled -> Level.TRACE
                configuration.level == Level.DEBUG && pipeline.log.isDebugEnabled -> Level.DEBUG
                configuration.level == Level.INFO && pipeline.log.isInfoEnabled -> Level.INFO
                else -> throw IllegalArgumentException("The ${configuration.level} log level is not supported.")
            }
            val feature = CallLogging(pipeline.log, pipeline.environment.monitor, level)
            pipeline.insertPhaseBefore(ApplicationCallPipeline.Infrastructure, loggingPhase)
            pipeline.intercept(loggingPhase) {
                proceed()
                feature.logSuccess(call)
            }
            return feature
        }
    }

    private fun log(message: String) {
        if (level == Level.INFO) log.info(message)
        if (level == Level.DEBUG) log.debug(message)
        if (level == Level.TRACE) log.trace(message)
    }

    private fun logSuccess(call: ApplicationCall) {
        val status = call.response.status() ?: "Unhandled"
        when (status) {
            HttpStatusCode.Found -> log("$status: ${call.request.logInfo()} -> ${call.response.headers[HttpHeaders.Location]}")
            else -> log("$status: ${call.request.logInfo()}")
        }
    }
}

fun ApplicationRequest.logInfo() = "${httpMethod.value} - ${path()}"
