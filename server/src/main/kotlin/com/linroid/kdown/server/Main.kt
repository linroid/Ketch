package com.linroid.kdown.server

import com.linroid.kdown.DownloadConfig
import com.linroid.kdown.KDown
import com.linroid.kdown.SpeedLimit
import com.linroid.kdown.engine.KtorHttpEngine
import com.linroid.kdown.log.Logger
import com.linroid.kdown.sqlite.DriverFactory
import com.linroid.kdown.sqlite.SqliteTaskStore
import java.io.File

fun main(args: Array<String>) {
  var host = "0.0.0.0"
  var port = 8642
  var token: String? = null
  var corsOrigins: List<String> = emptyList()
  var downloadDir = "downloads"
  var speedLimit = SpeedLimit.Unlimited

  var i = 0
  while (i < args.size) {
    when (args[i]) {
      "--help", "-h" -> {
        printUsage()
        return
      }
      "--host" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --host requires a value")
          printUsage()
          return
        }
        host = args[++i]
      }
      "--port" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --port requires a value")
          printUsage()
          return
        }
        val value = args[++i].toIntOrNull()
        if (value == null || value !in 1..65535) {
          System.err.println(
            "Error: invalid port '${args[i]}'"
          )
          return
        }
        port = value
      }
      "--token" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --token requires a value")
          printUsage()
          return
        }
        token = args[++i]
      }
      "--cors" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --cors requires a value")
          printUsage()
          return
        }
        corsOrigins = args[++i].split(",").map { it.trim() }
      }
      "--dir" -> {
        if (i + 1 >= args.size) {
          System.err.println("Error: --dir requires a value")
          printUsage()
          return
        }
        downloadDir = args[++i]
      }
      "--speed-limit" -> {
        if (i + 1 >= args.size) {
          System.err.println(
            "Error: --speed-limit requires a value"
          )
          printUsage()
          return
        }
        speedLimit = parseSpeedLimit(args[++i]) ?: run {
          System.err.println(
            "Error: invalid speed limit '${args[i]}'"
          )
          printUsage()
          return
        }
      }
      else -> {
        System.err.println(
          "Error: unknown option '${args[i]}'"
        )
        printUsage()
        return
      }
    }
    i++
  }

  // Ensure download directory exists
  File(downloadDir).mkdirs()

  val dbPath = File(downloadDir, "kdown.db").absolutePath
  val driver = DriverFactory(dbPath).createDriver()
  val taskStore = SqliteTaskStore(driver)

  val config = DownloadConfig(
    speedLimit = speedLimit
  )

  val kdown = KDown(
    httpEngine = KtorHttpEngine(),
    taskStore = taskStore,
    config = config,
    logger = Logger.console()
  )

  val serverConfig = KDownServerConfig(
    host = host,
    port = port,
    apiToken = token,
    corsAllowedHosts = corsOrigins
  )

  val server = KDownServer(kdown, serverConfig)

  Runtime.getRuntime().addShutdownHook(Thread {
    println("Shutting down KDown server...")
    server.stop()
    kdown.close()
  })

  println("KDown Server v${KDown.VERSION}")
  println("  Host:          $host")
  println("  Port:          $port")
  println("  Download dir:  $downloadDir")
  println("  Database:      $dbPath")
  if (token != null) {
    println("  Auth:          enabled")
  }
  if (corsOrigins.isNotEmpty()) {
    println("  CORS origins:  ${corsOrigins.joinToString(", ")}")
  }
  if (!speedLimit.isUnlimited) {
    println(
      "  Speed limit:   " +
        "${speedLimit.bytesPerSecond / 1024} KB/s"
    )
  }
  println()

  server.start(wait = true)
}

private fun printUsage() {
  println("Usage: kdown-server [options]")
  println()
  println("Options:")
  println("  --host <address>       Bind address (default: 0.0.0.0)")
  println("  --port <number>        Port number (default: 8642)")
  println("  --token <string>       API bearer token (optional)")
  println("  --cors <origins>       CORS allowed origins,")
  println("                         comma-separated (optional)")
  println("  --dir <path>           Download directory")
  println("                         (default: ./downloads)")
  println("  --speed-limit <value>  Global speed limit")
  println("                         (e.g., 10m, 500k)")
  println("  --help, -h             Show this help message")
  println()
  println("Examples:")
  println("  kdown-server")
  println("  kdown-server --port 9000 --dir /tmp/downloads")
  println("  kdown-server --token my-secret --cors '*'")
  println("  kdown-server --speed-limit 10m")
}

private fun parseSpeedLimit(value: String): SpeedLimit? {
  val trimmed = value.trim().lowercase()
  return when {
    trimmed.endsWith("m") -> {
      val num = trimmed.dropLast(1).toLongOrNull()
        ?: return null
      if (num <= 0) return null
      SpeedLimit.mbps(num)
    }
    trimmed.endsWith("k") -> {
      val num = trimmed.dropLast(1).toLongOrNull()
        ?: return null
      if (num <= 0) return null
      SpeedLimit.kbps(num)
    }
    else -> {
      val num = trimmed.toLongOrNull() ?: return null
      if (num <= 0) return null
      SpeedLimit.of(num)
    }
  }
}
