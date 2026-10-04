package com.linroid.ketch.ai

import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.reflect.ParameterizedType
import java.util.jar.JarFile
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks the GraalVM reflection metadata the native `ketch ai-discover` needs. */
class NativeImageConfigTest {

  /**
   * Koog's Gemini and OpenAI Responses clients encode request parts with content-polymorphic
   * serializers, which find the serializer of each concrete class through reflection. The
   * native binary needs every such class registered, including the ones a newer Koog adds.
   */
  @Test
  fun `every subtype of a content-polymorphic Koog type is registered for reflection`() {
    val subtypes = contentPolymorphicBaseTypes().flatMap { it.kotlin.concreteSubclasses() }
    assertTrue(subtypes.isNotEmpty(), "found no content-polymorphic types in the LLM clients")

    val registered = registeredNames()
    val missing = subtypes.flatMap { lookupClassNames(it.java) }.filter { it !in registered }
    assertEquals(emptyList(), missing)
  }

  /** Base types of the content-polymorphic serializers in the clients [LlmClientFactory] uses. */
  private fun contentPolymorphicBaseTypes(): List<Class<*>> {
    val clients = listOf(
      AnthropicLLMClient::class,
      GoogleLLMClient::class,
      OpenAILLMClient::class,
      OllamaClient::class,
    )
    return clients.map { File(it.java.protectionDomain.codeSource.location.toURI()) }
      .distinct()
      .flatMap(::classNames)
      .map { Class.forName(it, false, javaClass.classLoader) }
      .filter { JsonContentPolymorphicSerializer::class.java.isAssignableFrom(it) }
      .map { (it.genericSuperclass as ParameterizedType).actualTypeArguments.single() as Class<*> }
  }

  private fun classNames(jar: File): List<String> = JarFile(jar).use { file ->
    file.entries().asSequence()
      .map { it.name }
      .filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
      .map { it.removeSuffix(".class").replace('/', '.') }
      .toList()
  }

  private fun KClass<*>.concreteSubclasses(): List<KClass<*>> =
    sealedSubclasses.flatMap { if (it.isSealed) it.concreteSubclasses() else listOf(it) }

  /** Classes kotlinx.serialization reflects on to find the serializer of [type]. */
  private fun lookupClassNames(type: Class<*>): List<String> {
    // A class serializer is found through its companion, an object's through INSTANCE
    val companion = type.declaredFields.find { it.name == "Companion" }
    return listOfNotNull(type.name, companion?.type?.name)
  }

  private fun registeredNames(): Set<String> {
    val path = "META-INF/native-image/com.linroid.ketch.ai.discover/reflect-config.json"
    val config = assertNotNull(javaClass.classLoader.getResource(path), "$path is missing")
    return Json.parseToJsonElement(config.readText()).jsonArray
      .map { it.jsonObject.getValue("name").jsonPrimitive.content }
      .toSet()
  }
}
