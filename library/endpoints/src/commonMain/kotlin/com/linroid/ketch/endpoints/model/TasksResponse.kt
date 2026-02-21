package com.linroid.ketch.endpoints.model

import kotlinx.serialization.Serializable


@Serializable
data class TasksResponse(
  val tasks: List<TaskSnapshot>
)
