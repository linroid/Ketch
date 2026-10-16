package com.linroid.ketch.core.digest

import com.linroid.ketch.api.ChecksumAlgorithm

/** No platform digests here: every algorithm is pure Kotlin. */
internal actual fun platformDigest(algorithm: ChecksumAlgorithm): MessageDigest? = null
