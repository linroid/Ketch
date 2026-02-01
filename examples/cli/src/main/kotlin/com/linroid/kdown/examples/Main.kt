package com.linroid.kdown.examples

import com.linroid.kdown.KDown

fun main(args: Array<String>) {
  println("KDown CLI Example - Version ${KDown.VERSION}")
  println()

  if (args.isEmpty()) {
    println("Usage: kdown-cli <url> [destination]")
    println()
    println("Examples:")
    println("  kdown-cli https://example.com/file.zip")
    println("  kdown-cli https://example.com/file.zip ./downloads/file.zip")
    return
  }

  val url = args[0]
  val destination = args.getOrNull(1) ?: url.substringAfterLast("/")

  println("Downloading: $url")
  println("Destination: $destination")
  println()

  // TODO: Implement download using KDown library
  println("Download functionality will be implemented with the KDown library.")
}
