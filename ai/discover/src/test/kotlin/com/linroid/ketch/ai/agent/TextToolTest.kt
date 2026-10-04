package com.linroid.ketch.ai.agent

import ai.koog.agents.core.tools.ToolException
import ai.koog.serialization.kotlinx.KotlinxSerializer
import ai.koog.serialization.kotlinx.toKoogJSONObject
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextToolTest {

  private val serializer = KotlinxSerializer()

  private val repeat = TextTool(
    name = "repeat",
    description = "Repeats a word",
    parameters = listOf(
      stringParameter("word", "The word"),
      integerParameter("times", "How often", required = false),
    ),
  ) { List(int("times", 2)) { string("word") }.joinToString(" ") }

  @Test
  fun descriptor_parameterWithDefault_isOptional() {
    assertEquals(listOf("word"), repeat.descriptor.requiredParameters.map { it.name })
    assertEquals(listOf("times"), repeat.descriptor.optionalParameters.map { it.name })
  }

  @Test
  fun call_leftOutOrNullArgument_usesTheDefault() = runTest {
    assertEquals("a a", call(buildJsonObject { put("word", "a") }))
    assertEquals("a a", call(buildJsonObject { put("word", "a"); put("times", JsonNull) }))
  }

  @Test
  fun call_integerWrittenAsTextOrWholeNumber_isAccepted() = runTest {
    assertEquals("a a a", call(buildJsonObject { put("word", "a"); put("times", "3") }))
    assertEquals("a a a", call(buildJsonObject { put("word", "a"); put("times", 3.0) }))
  }

  @Test
  fun call_missingOrMalformedArgument_failsValidation() = runTest {
    assertFailsWith<ToolException.ValidationFailure> { call(buildJsonObject {}) }
    assertFailsWith<ToolException.ValidationFailure> {
      call(buildJsonObject { put("word", "a"); put("times", 1.5) })
    }
    assertFailsWith<ToolException.ValidationFailure> {
      call(buildJsonObject { put("word", buildJsonArray {}) })
    }
  }

  @Test
  fun encodeResultToString_jsonText_isNotEncodedAgain() {
    assertEquals("""{"ok":true}""", repeat.encodeResultToString("""{"ok":true}""", serializer))
  }

  private suspend fun call(arguments: JsonObject): String =
    repeat.execute(repeat.decodeArgs(arguments.toKoogJSONObject(), serializer))
}
