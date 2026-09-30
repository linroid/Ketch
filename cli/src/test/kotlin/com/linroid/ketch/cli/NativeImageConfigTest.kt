package com.linroid.ketch.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Checks the GraalVM reflection metadata of the native `ketch` binary against the code. */
class NativeImageConfigTest {
  private val configs = listOf(
    "META-INF/native-image/com.linroid.ketch.cli/reflect-config.json",
    "META-INF/native-image/com.linroid.ketch.mcp/reflect-config.json",
    "META-INF/native-image/com.linroid.ketch.ai.discover/reflect-config.json",
  )

  @Test
  fun `reflection metadata only names classes and members that exist`() {
    val loader = javaClass.classLoader
    val missing = configs.flatMap { path ->
      val config = assertNotNull(loader.getResource(path), "$path is not on the classpath")
      Json.parseToJsonElement(config.readText()).jsonArray.flatMap { entry ->
        missingMembers(entry.jsonObject, loader).map { "$path: $it" }
      }
    }
    assertEquals(emptyList(), missing)
  }

  private fun missingMembers(entry: JsonObject, loader: ClassLoader): List<String> {
    val name = entry.getValue("name").jsonPrimitive.content
    val type = try {
      Class.forName(name, false, loader)
    } catch (_: ClassNotFoundException) {
      return listOf(name)
    }
    val fields = type.declaredFields.map { it.name }.toSet()
    val methods = type.declaredMethods.map { it.name }.toSet()
    return memberNames(entry, "fields").filter { it !in fields }.map { "$name.$it" } +
      memberNames(entry, "methods").filter { it !in methods }.map { "$name.$it()" }
  }

  private fun memberNames(entry: JsonObject, key: String): List<String> =
    entry[key]?.jsonArray.orEmpty().map { it.jsonObject.getValue("name").jsonPrimitive.content }
}
