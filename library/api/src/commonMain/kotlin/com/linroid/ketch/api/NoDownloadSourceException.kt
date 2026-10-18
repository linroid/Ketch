package com.linroid.ketch.api

/**
 * The cause of the [KetchError.Unsupported] that [KetchApi.resolve] throws for an input no
 * download source takes, on an instance in this process or a remote one. It tells that apart from
 * the one a source throws when it takes the input but lacks something it needs, such as byte
 * ranges or a usable proxy, whose cause says what, so callers say that no source handles an input
 * only when none does.
 */
class NoDownloadSourceException @InternalKetchApi constructor() :
  Exception("No download source takes this input")
