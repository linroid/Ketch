package com.linroid.ketch.mcp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class StdioTransportTest {

  @Test
  fun `start reads nothing until startReading`() =
    runTest(timeout = 10.seconds) {
      val read = CompletableDeferred<Unit>()
      val input = object : RawSource {
        override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
          read.complete(Unit)
          return -1
        }

        override fun close() {}
      }
      val transport = StdioTransport(input, Buffer())
      try {
        transport.start()
        // Real time: an input that ended must not close the transport before the session is set up
        withContext(Dispatchers.IO) {
          assertNull(withTimeoutOrNull(200.milliseconds) { read.await() })
        }

        transport.startReading()
        read.await()
      } finally {
        transport.close()
      }
    }
}
