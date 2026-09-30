package com.linroid.ketch.mcp

import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class NativeImageConfigTest {

  /**
   * The MCP SDK serializes its sealed message types with content-polymorphic serializers,
   * which find the serializer of each concrete class through reflection. The native binary
   * needs every such class registered, including the ones a newer SDK adds.
   */
  @Test
  fun `every serializable MCP SDK type is registered for reflection`() {
    val path = "META-INF/native-image/com.linroid.ketch.mcp/reflect-config.json"
    val config = assertNotNull(javaClass.classLoader.getResource(path), "$path is missing")
    val registered = Json.parseToJsonElement(config.readText()).jsonArray
      .map { it.jsonObject.getValue("name").jsonPrimitive.content }
      .toSet()

    val missing = sdkTypeNames().filter { hasSerializerLookup(it) && it !in registered }
    assertEquals(emptyList(), missing)
  }

  private fun sdkTypeNames(): List<String> {
    val jar = File(RequestId::class.java.protectionDomain.codeSource.location.toURI())
    return JarFile(jar).use { file ->
      file.entries().asSequence()
        .map { it.name }
        .filter { it.startsWith("io/modelcontextprotocol/kotlin/sdk/types/") }
        .filter { it.endsWith(".class") }
        .map { it.removeSuffix(".class").replace('/', '.') }
        .toList()
    }
  }

  /** Whether kotlinx.serialization looks up the serializer of [name] reflectively. */
  private fun hasSerializerLookup(name: String): Boolean {
    val type = Class.forName(name, false, javaClass.classLoader)
    // A class serializer is found through its companion, an object's through INSTANCE
    val holder = type.declaredFields.find { it.name == "Companion" || it.name == "INSTANCE" }
      ?: return false
    return holder.type.declaredMethods.any {
      it.name == "serializer" && it.parameterCount == 0 &&
        KSerializer::class.java.isAssignableFrom(it.returnType)
    }
  }
}
