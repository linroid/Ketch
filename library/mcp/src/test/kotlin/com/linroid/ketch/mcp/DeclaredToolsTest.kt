package com.linroid.ketch.mcp

import ai.koog.agents.core.tools.Tool
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import ai.koog.agents.core.tools.annotations.Tool as ToolMethod

// KetchMcpServerTest covers what KetchToolSet relies on: String results and parameters that
// are either plain or have a default value.
class DeclaredToolsTest {

  private val serializer = KotlinxSerializer()
  private val tools = SampleToolSet().asDeclaredTools().associateBy { it.name }

  @Test
  fun `a nullable parameter without a default value stays required`() {
    val descriptor = tools.getValue("greet").descriptor

    // Koog calls the method with callBy, which only fills in declared defaults
    assertEquals(listOf("name", "title"), descriptor.requiredParameters.map { it.name })
    assertEquals(listOf("greeting"), descriptor.optionalParameters.map { it.name })
  }

  @Test
  fun `a result that is not a String is still encoded as JSON`() = runTest {
    val result = tools.getValue("countTo").call(buildJsonObject { put("limit", 3) })

    assertEquals("[1,2,3]", result)
  }

  private suspend fun <TArgs, TResult> Tool<TArgs, TResult>.call(arguments: JsonObject): String {
    val result = execute(decodeArgs(arguments.toKoogJSONObject(), serializer))
    return encodeResultToString(result, serializer)
  }
}

// Not private: Koog calls the methods through reflection
internal class SampleToolSet : ToolSet {

  @ToolMethod
  @LLMDescription("Greets someone")
  fun greet(name: String, title: String?, greeting: String = "Hello"): String =
    listOfNotNull(greeting, title, name).joinToString(" ")

  @ToolMethod
  @LLMDescription("Counts from one to a limit")
  fun countTo(limit: Int): List<Int> = (1..limit).toList()
}
