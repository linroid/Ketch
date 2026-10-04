package com.linroid.ketch.ai.agent

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolException
import ai.koog.agents.core.tools.ToolParameterDescriptor
import ai.koog.agents.core.tools.ToolParameterType
import ai.koog.serialization.JSONElement
import ai.koog.serialization.JSONNull
import ai.koog.serialization.JSONObject
import ai.koog.serialization.JSONPrimitive
import ai.koog.serialization.JSONSerializer
import ai.koog.serialization.TypeToken

/**
 * A tool that takes named string and integer arguments and answers with text, described by hand.
 *
 * Koog's `ToolSet` describes and calls `@Tool` methods through Kotlin reflection, which needs
 * kotlin-reflect: megabytes in the desktop app and the native CLI. A [TextTool] needs none.
 *
 * Its result is passed on as it is, so a tool that returns a JSON document yields that document,
 * not a JSON string holding it. A missing or malformed argument fails the call with a
 * [ToolException.ValidationFailure] that names it.
 *
 * `library:mcp` has the same class; the two modules share no module that depends on Koog.
 */
internal class TextTool(
  name: String,
  description: String,
  parameters: List<Parameter>,
  private val run: suspend Arguments.() -> String,
) : Tool<JSONObject, String>(
  argsType = TypeToken.of(JSONObject::class.java),
  resultType = TypeToken.of(String::class.java),
  descriptor = ToolDescriptor(
    name = name,
    description = description,
    requiredParameters = parameters.filter { it.required }.map { it.descriptor },
    optionalParameters = parameters.filterNot { it.required }.map { it.descriptor },
  ),
) {

  /** A parameter of a [TextTool]; an optional one is one the tool has a default value for. */
  class Parameter(val descriptor: ToolParameterDescriptor, val required: Boolean)

  /** The arguments of one call. A `null` argument counts as left out. */
  class Arguments(private val values: JSONObject) {

    fun string(name: String): String = primitive(name)?.content ?: missing(name)

    fun string(name: String, default: String): String = primitive(name)?.content ?: default

    fun int(name: String): Int = primitive(name)?.toInt(name) ?: missing(name)

    fun int(name: String, default: Int): Int = primitive(name)?.toInt(name) ?: default

    private fun primitive(name: String): JSONPrimitive? = when (val value = values.entries[name]) {
      null, JSONNull -> null
      is JSONPrimitive -> value
      else -> throw ToolException.ValidationFailure("Argument '$name' must be a single value")
    }

    private fun JSONPrimitive.toInt(name: String): Int = content.toIntOrNull()
      ?: content.toDoubleOrNull()?.takeIf { it % 1.0 == 0.0 }?.toInt()
      ?: throw ToolException.ValidationFailure("Argument '$name' must be an integer")

    private fun missing(name: String): Nothing =
      throw ToolException.ValidationFailure("Missing argument '$name'")
  }

  override fun decodeArgs(rawArgs: JSONObject, serializer: JSONSerializer): JSONObject = rawArgs

  override fun encodeArgs(args: JSONObject, serializer: JSONSerializer): JSONObject = args

  override fun decodeResult(rawResult: JSONElement, serializer: JSONSerializer): String =
    (rawResult as? JSONPrimitive)?.content ?: rawResult.toString()

  override fun encodeResult(result: String, serializer: JSONSerializer): JSONElement =
    JSONPrimitive.of(result)

  override fun encodeResultToString(result: String, serializer: JSONSerializer): String = result

  override suspend fun execute(args: JSONObject): String = Arguments(args).run()
}

internal fun stringParameter(name: String, description: String, required: Boolean = true) =
  TextTool.Parameter(ToolParameterDescriptor(name, description, ToolParameterType.String), required)

internal fun integerParameter(name: String, description: String, required: Boolean = true) =
  TextTool.Parameter(ToolParameterDescriptor(name, description, ToolParameterType.Integer), required)
