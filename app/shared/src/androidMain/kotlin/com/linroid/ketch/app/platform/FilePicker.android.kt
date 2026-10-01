package com.linroid.ketch.app.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@Composable
actual fun rememberFilePicker(): FilePicker {
  val context = LocalContext.current
  val folderRequest = remember { PickRequest<Uri?>(canceled = null) }
  val torrentRequest = remember { PickRequest<List<Uri>>(canceled = emptyList()) }
  val folderLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree(),
    onResult = folderRequest::complete,
  )
  val torrentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenMultipleDocuments(),
    onResult = torrentRequest::complete,
  )
  DisposableEffect(Unit) {
    onDispose {
      folderRequest.cancel()
      torrentRequest.cancel()
    }
  }
  return remember(context, folderLauncher, torrentLauncher) {
    AndroidFilePicker(context, folderRequest, folderLauncher, torrentRequest, torrentLauncher)
  }
}

/** The Storage Access Framework pickers. */
private class AndroidFilePicker(
  private val context: Context,
  private val folderRequest: PickRequest<Uri?>,
  private val folderLauncher: ManagedActivityResultLauncher<Uri?, Uri?>,
  private val torrentRequest: PickRequest<List<Uri>>,
  private val torrentLauncher: ManagedActivityResultLauncher<Array<String>, List<Uri>>,
) : FilePicker {
  override val canPickFolder: Boolean = true

  override suspend fun pickFolder(initialFolder: String?): String? {
    val initial = initialFolder?.takeIf { it.startsWith(CONTENT_SCHEME) }
      ?.let { treeRoot(Uri.parse(it)) }
    val uri = folderRequest.launch { folderLauncher.launch(initial) } ?: return null
    // Without a persisted grant, downloads could no longer write there after a restart.
    val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    context.contentResolver.takePersistableUriPermission(uri, access)
    return uri.toString()
  }

  override suspend fun pickTorrentFiles(): List<DroppedFile> {
    val uris = torrentRequest.launch { torrentLauncher.launch(TORRENT_MIME_TYPES) }
    return withContext(Dispatchers.IO) { uris.map { contentFile(context, it) } }
  }

  /** The root document of the folder [tree], where the picker can start; `null` if no tree. */
  private fun treeRoot(tree: Uri): Uri? = try {
    DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
  } catch (e: IllegalArgumentException) {
    null
  }

  private companion object {
    // Many providers report .torrent files as plain binary data.
    val TORRENT_MIME_TYPES = arrayOf("application/x-bittorrent", "application/octet-stream")
  }
}

/**
 * One picker request at a time: a new request ends the one still waiting with [canceled]. Runs on
 * the main thread, where results arrive.
 */
private class PickRequest<T>(private val canceled: T) {
  private var pending: CompletableDeferred<T>? = null

  suspend fun launch(start: () -> Unit): T = withContext(Dispatchers.Main.immediate) {
    pending?.complete(canceled)
    val result = CompletableDeferred<T>()
    pending = result
    try {
      start()
      result.await()
    } finally {
      if (pending === result) pending = null
    }
  }

  fun complete(value: T) {
    pending?.complete(value)
  }

  /** Ends a request whose picker can no longer report back, as its screen is gone. */
  fun cancel() {
    pending?.cancel()
  }
}

/** The document at [uri] as a [DroppedFile]; its name is queried now, its content on demand. */
private fun contentFile(context: Context, uri: Uri): DroppedFile {
  val name = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
      ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
  }.getOrNull() ?: uri.lastPathSegment ?: uri.toString()
  return DroppedFile(name) { maxBytes ->
    withContext(Dispatchers.IO) {
      val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalArgumentException("Cannot open $name")
      input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val read = stream.read(buffer)
          if (read < 0) break
          output.write(buffer, 0, read)
          if (output.size() > maxBytes) fileTooLarge(name, maxBytes)
        }
        output.toByteArray()
      }
    }
  }
}
