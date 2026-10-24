package com.linroid.ketch.endpoints.stream

import com.linroid.ketch.api.Interest
import com.linroid.ketch.torrent.api.TorrentInterest
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * The [Interest]s a [StreamSubscription] carries, by the serial names their classes declare
 * (`type`): include it in the JSON of servers and clients. Interests of an unknown type decode as
 * [UnknownInterest].
 */
val StreamSerializers: SerializersModule = SerializersModule {
  polymorphic(Interest::class) {
    subclass(Interest.Segments::class)
    subclass(Interest.Stats::class)
    subclass(Interest.Connections::class)
    subclass(Interest.SpeedMode::class)
    subclass(TorrentInterest::class)
    subclass(UnknownInterest::class)
    defaultDeserializer { UnknownInterest.serializer() }
  }
}
