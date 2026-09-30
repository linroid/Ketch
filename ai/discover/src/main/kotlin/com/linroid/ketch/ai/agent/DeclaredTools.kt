package com.linroid.ketch.ai.agent

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.reflect.ToolFromCallable
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONSerializer
import kotlin.reflect.KCallable

/**
 * The `@Tool` methods of this [ToolSet] as tools that match how the methods are declared.
 *
 * [ToolSet.asTools] (Koog 1.2.0) differs from the methods in two ways:
 * - Its descriptors list every parameter as required. A call may still leave out a
 *   parameter that has a default value, so those are described as optional here.
 * - It encodes every result as JSON, which turns the text a method returns into a quoted
 *   and escaped JSON string. A `String` result is passed on as it is here, so a method that
 *   returns a JSON document yields that document.
 *
 * `library:mcp` has the same adapter; the two modules share no module that depends on Koog.
 */
internal fun ToolSet.asDeclaredTools(): List<Tool<*, *>> = asTools().map { DeclaredTool(it) }

private class DeclaredTool<TResult>(
  private val tool: ToolFromCallable<TResult>,
) : Tool<ToolFromCallable.Args, TResult>(
  argsType = tool.argsType,
  resultType = tool.resultType,
  descriptor = tool.descriptor.withOptionalDefaults(tool.callable),
  metadata = tool.metadata,
) {

  override fun decodeArgs(rawArgs: JSONObject, serializer: JSONSerializer): ToolFromCallable.Args =
    tool.decodeArgs(rawArgs, serializer)

  override fun encodeArgs(args: ToolFromCallable.Args, serializer: JSONSerializer): JSONObject =
    tool.encodeArgs(args, serializer)

  override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): TResult =
    tool.decodeResult(rawResult, serializer)

  override fun encodeResult(result: TResult, serializer: JSONSerializer): JSONElement =
    tool.encodeResult(result, serializer)

  override fun encodeResultToString(result: TResult, serializer: JSONSerializer): String =
    result as? String ?: tool.encodeResultToString(result, serializer)

  override suspend fun execute(args: ToolFromCallable.Args): TResult = tool.execute(args)
}

/** Moves the parameters [callable] declares a default value for to the optional ones. */
private fun ToolDescriptor.withOptionalDefaults(callable: KCallable<*>): ToolDescriptor {
  val defaulted = callable.parameters.filter { it.isOptional }.map { it.name }.toSet()
  val (optional, required) = requiredParameters.partition { it.name in defaulted }
  return copy(requiredParameters = required, optionalParameters = optionalParameters + optional)
}
