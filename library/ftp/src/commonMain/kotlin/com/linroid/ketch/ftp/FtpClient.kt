package com.linroid.ketch.ftp

/**
 * Internal FTP protocol client abstraction.
 *
 * Manages a control connection for sending FTP commands and
 * supports opening data connections for file transfer in passive
 * mode.
 *
 * Implementations must be used from a single coroutine at a time
 * (one control connection per client instance).
 */
internal interface FtpClient {

  /** True if the control connection is established. */
  val isConnected: Boolean

  /**
   * Connects to the FTP server and reads the welcome banner.
   *
   * @throws com.linroid.ketch.api.KetchError.Network on
   *   connection failure
   */
  suspend fun connect()

  /**
   * Upgrades the control connection to TLS (AUTH TLS / PBSZ / PROT).
   *
   * @throws com.linroid.ketch.api.KetchError.Unsupported if TLS
   *   is not available on this platform
   * @throws com.linroid.ketch.api.KetchError.Network on TLS
   *   handshake failure
   */
  suspend fun upgradeToTls()

  /**
   * Authenticates with the FTP server (USER + PASS).
   *
   * @throws com.linroid.ketch.api.KetchError on authentication
   *   failure (mapped from FTP reply code)
   */
  suspend fun login(username: String, password: String)

  /**
   * Sets the transfer type to binary (TYPE I).
   */
  suspend fun setBinaryMode()

  /**
   * Queries the file size using the SIZE command.
   *
   * @return file size in bytes, or null if the server does not
   *   support SIZE or the file does not exist
   */
  suspend fun size(path: String): Long?

  /**
   * Queries the last modification time using the MDTM command.
   *
   * @return modification timestamp string (YYYYMMDDhhmmss), or
   *   null if the server does not support MDTM
   */
  suspend fun mdtm(path: String): String?

  /**
   * Checks whether the server supports the REST (restart) command
   * for resuming transfers.
   *
   * @return true if REST is supported
   */
  suspend fun supportsRest(): Boolean

  /**
   * Downloads a file (or a portion of a file) via RETR, delivering
   * data chunks through the callback.
   *
   * If [offset] is non-zero, a REST command is sent before RETR
   * to resume from that position.
   *
   * Uses passive mode (PASV or EPSV) for the data connection.
   *
   * @param path remote file path
   * @param offset byte offset to start from (0 for full download)
   * @param onData callback invoked for each chunk of received bytes
   * @throws com.linroid.ketch.api.KetchError on transfer failure
   */
  suspend fun retrieve(
    path: String,
    offset: Long = 0,
    onData: suspend (ByteArray) -> Unit,
  )

  /**
   * Sends QUIT and closes all connections.
   */
  suspend fun disconnect()
}
