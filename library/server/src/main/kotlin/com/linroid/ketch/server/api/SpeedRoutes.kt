package com.linroid.ketch.server.api

import com.linroid.ketch.api.SpeedModeChange
import com.linroid.ketch.api.SpeedModes
import com.linroid.ketch.endpoints.Api
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/** The instance's [SpeedModes]: their state, and changing them on the instance. */
internal fun Route.speedRoutes(modes: SpeedModes) {
  get<Api.Speed> {
    call.respond(modes.state())
  }

  put<Api.Speed> {
    val change = call.receiveJson<SpeedModeChange>()
    call.respond(modes.update(change))
  }
}
