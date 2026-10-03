package com.linroid.ketch.endpoints.model

import kotlinx.serialization.Serializable

/**
 * Asks the owner of a server with an access token to let the sending device control it, instead
 * of typing the token. The server shows [name] and [code] and, once its owner allows the
 * request, answers with the token in a [PairingStatus].
 *
 * @property name the sending device's name, such as "Pixel 9".
 * @property code four digits the sending device shows too, so the owner can tell its request
 *   from one another device sent at the same time.
 * @property os the sending device's system, such as "Android 16", or `null` when unknown.
 */
@Serializable
data class PairingRequest(
  val name: String,
  val code: String,
  val os: String? = null,
)

/**
 * The server's receipt for a [PairingRequest]: poll the request by [id] for the answer.
 *
 * @property id identifies the request; only its sender knows it.
 * @property expiresInSeconds how long the owner has to answer.
 */
@Serializable
data class PairingTicket(
  val id: String,
  val expiresInSeconds: Long,
)

/**
 * Where a [PairingRequest] stands.
 *
 * @property state whether the owner answered.
 * @property token the server's access token once the owner allowed the request, or `null`.
 */
@Serializable
data class PairingStatus(
  val state: PairingState,
  val token: String? = null,
)

/** Whether the owner of a server answered a [PairingRequest]. */
@Serializable
enum class PairingState {
  /** Waiting for the owner. */
  PENDING,

  /** The owner let the device in; [PairingStatus.token] holds the access token. */
  ALLOWED,

  /** The owner turned the device away. */
  DENIED,

  /** Nobody answered in time. */
  EXPIRED,
}
