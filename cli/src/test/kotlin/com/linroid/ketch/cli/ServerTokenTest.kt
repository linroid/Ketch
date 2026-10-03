package com.linroid.ketch.cli

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ServerTokenTest {
  private val dir = Files.createTempDirectory("ketch-token").toFile()
  private val tokenFile = File(dir, "config/$TOKEN_FILE_NAME")

  @AfterTest
  fun cleanup() {
    dir.deleteRecursively()
  }

  @Test
  fun resolve_beyondLoopbackWithoutToken_createsAndKeepsOne() {
    val created = assertIs<ServerToken.Created>(resolve())

    assertEquals(64, created.value.length)
    assertEquals(created.value, tokenFile.readText().trim())
    assertEquals(ServerToken.Saved(created.value, tokenFile), resolve())
  }

  @Test
  fun resolve_createdFile_onlyItsOwnerCanRead() {
    if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
    resolve()

    assertEquals("rw-------", permissions())
  }

  @Test
  fun resolve_savedFileOthersCanRead_madePrivate() {
    if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
    tokenFile.parentFile.mkdirs()
    tokenFile.writeText("saved-token\n")
    Files.setPosixFilePermissions(tokenFile.toPath(), PosixFilePermissions.fromString("rw-r--r--"))

    assertEquals(ServerToken.Saved("saved-token", tokenFile), resolve())
    assertEquals("rw-------", permissions())
  }

  @Test
  fun resolve_loopbackWithoutToken_none() {
    assertEquals(ServerToken.None, resolve(loopbackOnly = true))
    assertFalse(tokenFile.exists())
  }

  @Test
  fun resolve_noToken_noneEvenBeyondLoopbackOrWithAConfiguredToken() {
    assertEquals(ServerToken.None, resolve(noToken = true, configToken = "configured"))
    assertFalse(tokenFile.exists())
  }

  @Test
  fun resolve_givenTokens_flagThenEnvironmentThenConfig() {
    assertEquals(
      ServerToken.Given("flag", "--token"),
      resolve(cliToken = "flag", environmentToken = "env", configToken = "config"),
    )
    assertEquals(
      ServerToken.Given("env", TOKEN_ENV),
      resolve(environmentToken = "env", configToken = "config"),
    )
    assertEquals(ServerToken.Given("config", "apiToken"), resolve(configToken = "config"))
    assertFalse(tokenFile.exists())
  }

  @Test
  fun resolve_givenTokenOnLoopback_stillRequired() {
    assertEquals(
      ServerToken.Given("env", TOKEN_ENV),
      resolve(environmentToken = "env", loopbackOnly = true),
    )
  }

  @Test
  fun resolve_blankValues_countAsMissing() {
    val token = resolve(cliToken = " ", environmentToken = "", configToken = "  ")

    assertIs<ServerToken.Created>(token)
  }

  private fun permissions(): String =
    PosixFilePermissions.toString(Files.getPosixFilePermissions(tokenFile.toPath()))

  private fun resolve(
    cliToken: String? = null,
    noToken: Boolean = false,
    environmentToken: String? = null,
    configToken: String? = null,
    loopbackOnly: Boolean = false,
  ): ServerToken = resolveServerToken(
    cliToken = cliToken,
    noToken = noToken,
    environmentToken = environmentToken,
    configToken = configToken,
    loopbackOnly = loopbackOnly,
    tokenFile = tokenFile,
  )
}
