package com.linroid.ketch.ai

import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import com.linroid.ketch.ai.agent.DiscoveryToolSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.TypeVariable
import java.lang.reflect.WildcardType
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

  /**
   * Koog lists a tool set's tools through kotlin-reflect, which loads every class in the tool
   * set's member signatures: private members and generic type arguments included. The native
   * binary fails to list the tools when one of them is not registered.
   */
  @Test
  fun `every Ketch class in the tool set signatures is registered for reflection`() {
    val toolSet = DiscoveryToolSet::class.java
    val signatures = toolSet.declaredConstructors.flatMap { it.genericParameterTypes.asList() } +
      toolSet.declaredFields.map { it.genericType } +
      toolSet.declaredMethods.flatMap { it.genericParameterTypes.asList() + it.genericReturnType }
    val ketchClasses = signatures.flatMap(::classesIn).map { it.name }
      .filter { it.startsWith("com.linroid.ketch.") }
      .distinct()
    assertTrue(ketchClasses.isNotEmpty(), "found no Ketch classes in the tool set")

    val registered = registeredNames()
    assertEquals(emptyList(), ketchClasses.filter { it !in registered })
  }

  /** The classes [type] names, its type arguments and bounds included. */
  private fun classesIn(type: Type): List<Class<*>> = when (type) {
    is Class<*> -> if (type.isArray) classesIn(type.componentType) else listOf(type)
    is ParameterizedType -> classesIn(type.rawType) + type.actualTypeArguments.flatMap(::classesIn)
    is WildcardType -> (type.upperBounds + type.lowerBounds).flatMap(::classesIn)
    is GenericArrayType -> classesIn(type.genericComponentType)
    is TypeVariable<*> -> type.bounds.flatMap(::classesIn)
    else -> emptyList()
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
