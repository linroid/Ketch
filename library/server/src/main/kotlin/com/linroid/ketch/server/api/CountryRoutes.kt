package com.linroid.ketch.server.api

import com.linroid.ketch.api.CountryDatabase
import com.linroid.ketch.endpoints.Api
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/** The instance's [CountryDatabase]: its status, and downloading it on the instance. */
internal fun Route.countryRoutes(database: CountryDatabase) {
  get<Api.Countries> {
    call.respond(database.status())
  }

  post<Api.Countries.Download> {
    call.respond(database.download())
  }
}
