package io.agamis.fusion.apps.engine

import com.caoccao.javet.exceptions.JavetException
import com.caoccao.javet.interop.NodeRuntime
import com.caoccao.javet.interop.engine.JavetEngineConfig
import com.caoccao.javet.interop.engine.JavetEnginePool
import io.agamis.fusion.apps.common.JavetLogger
import org.slf4j.Logger

import java.io.File
import com.caoccao.javet.interop.V8Host
import io.agamis.fusion.apps.common.enums.NodeReturnType._
import com.caoccao.javet.interop.executors.IV8Executor
import java.util.concurrent.atomic.AtomicBoolean
import io.agamis.fusion.apps.engine.exceptions.InstantiateException
import com.caoccao.javet.values.reference.V8ValueObject
import com.caoccao.javet.interop.V8Runtime

protected class JavetContainer[T](implicit _logger: Logger, config: JavetEngineConfig) {
    private var logger: JavetLogger = new JavetLogger()
    config.setJavetLogger(logger)
    protected var _running: AtomicBoolean = new AtomicBoolean(false)
    private var _runtime: NodeRuntime = new JavetEnginePool(config).getEngine().getV8Runtime()

    // Intercept calls to slf4j to log on scala side to provided logger
    val v8ValueObject: V8ValueObject = _runtime.createV8ValueObject()
    _runtime.getGlobalObject().set("slf4j", v8ValueObject)
    v8ValueObject.bind(logger)

    def runtime: NodeRuntime = _runtime

    def isRunning: AtomicBoolean = _running

    private def run(lambda: () => T): T = {
        return lambda()
    }
}

object JavetContainer {
    /**
      * Instantiate a new container running nodejs script meant to return provided type
      *
      * @param script the script file to run
      * @param returnType return type (default is VOID)
      * @param _logger a slf4j logger instance
      * @param config a JavetEngine configuration
      * @return container of a javet instance running provided script
      * @throws InstantiateException 
      */
    def ofScriptFile(script: File, returnType: NodeReturnType = VOID)(implicit _logger: Logger, config: JavetEngineConfig): JavetContainer[_] = {
        val infoMsg = ">> JavetContainer#ofScriptFile(File, NodeReturnType) > Running NodeJS script from file:`%s`"
        val errMsg = "<< JavetContainer#ofScriptFile(File, NodeReturnType) > Failed running NodeJS script from file:`%s`"
        val scriptPath = script.getPath()
        val container = new JavetContainer[Unit]()
        returnType match {
            case VOID => {
                container.run(() => {
                    _logger.info(String.format(infoMsg, scriptPath))
                    try {
                        container.runtime.getExecutor(script).executeVoid()
                    } catch {
                        case e: JavetException => {
                            _logger.error(String.format(errMsg, scriptPath), e)
                            throw InstantiateException(String.format("Failed instantiate `%s`", scriptPath), e)
                        }
                    }
                })
            }
        }
        return container
    }

    def ofProcessFile(process: File)(implicit _logger: Logger, config: JavetEngineConfig): JavetProcessContainer = {
        val infoMsg = ">> JavetContainer#ofProcessFile(File) > Running NodeJS process from file:`%s`"
        val errMsg = "<< JavetContainer#ofProcessFile(File) > Failed running NodeJS process from file:`%s`"
        val filePath = process.getPath()
        val container = new JavetProcessContainer()
        container.run(() => {
            _logger.info(String.format(infoMsg, filePath))
            try {
                container.runtime.getExecutor(process).executeVoid()
                container._running.set(true)
                container.runtime.await()
            } catch {
                case e: JavetException => {
                    _logger.error(String.format(errMsg, filePath), e)
                    throw InstantiateException(String.format("Failed instantiate `%s`", filePath), e)
                }
            }
        })
        return container
    }
}
