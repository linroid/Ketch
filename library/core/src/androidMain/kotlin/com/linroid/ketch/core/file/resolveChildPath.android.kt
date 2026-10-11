@file:Suppress("UseKtx")

package com.linroid.ketch.core.file

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.core.AndroidContext
import okio.Path.Companion.toPath

/** Held while a folder is looked up and created, so two downloads never both create it. */
private val folderLock = Any()

internal actual fun resolveChildPath(
  directory: String,
  fileName: String,
): String {
  if (':' !in directory) return (directory.toPath() / fileName).toString()
  val uri = Uri.parse(directory)
  if (uri.scheme == "content") {
    val docUri = DocumentsContract.createDocument(
      AndroidContext.get().contentResolver,
      parentDocument(uri),
      "application/octet-stream",
      fileName,
    ) ?: throw IllegalStateException(
      "Failed to create document '$fileName' in $directory"
    )
    return docUri.toString()
  }
  return (directory.toPath() / fileName).toString()
}

internal actual fun resolveChildFolder(
  directory: String,
  folderName: String,
): String {
  if (!isContentUri(directory)) return (directory.toPath() / folderName).toString()
  val uri = Uri.parse(directory)
  // Only a tree can be searched for the folder; creating it blindly would add another each time.
  if (!DocumentsContract.isTreeUri(uri)) return directory
  val resolver = AndroidContext.get().contentResolver
  val parent = parentDocument(uri)
  synchronized(folderLock) {
    findChildFolder(resolver, parent, folderName)?.let { return it.toString() }
    val folder = DocumentsContract.createDocument(
      resolver,
      parent,
      Document.MIME_TYPE_DIR,
      folderName,
    ) ?: throw IllegalStateException("Failed to create folder '$folderName' in $directory")
    return folder.toString()
  }
}

internal actual fun isContentUri(directory: String): Boolean =
  directory.startsWith("content://")

internal actual fun createPartialDocument(directory: String, fileName: String): String? {
  if (!isContentUri(directory)) return null
  val partialName = partialFileName(fileName) ?: return null
  val resolver = AndroidContext.get().contentResolver
  val document = DocumentsContract.createDocument(
    resolver,
    parentDocument(Uri.parse(directory)),
    "application/octet-stream",
    partialName,
  ) ?: throw IllegalStateException("Failed to create document '$partialName' in $directory")
  if ((flags(resolver, document) and Document.FLAG_SUPPORTS_RENAME) != 0) {
    return document.toString()
  }
  // It could never get its final name, so the download writes to a document under that name.
  log.d { "Provider cannot rename documents, writing under the final name: $directory" }
  try {
    DocumentsContract.deleteDocument(resolver, document)
  } catch (e: Exception) {
    log.w { "Failed to delete an unused partial document: ${e.describeCauses()}" }
  }
  return null
}

internal actual fun finishPartialDocument(partial: String): String? {
  val uri = Uri.parse(partial)
  val resolver = AndroidContext.get().contentResolver
  return try {
    val name = displayName(resolver, uri) ?: return null
    val finalName = name.removeSuffix(PARTIAL_FILE_SUFFIX)
    if (finalName == name || finalName.isEmpty()) return null
    // Providers pick a free name when it is taken; a null result keeps the document's URI.
    (DocumentsContract.renameDocument(resolver, uri, finalName) ?: uri).toString()
  } catch (e: Exception) {
    log.w { "Failed to rename a finished document: ${e.describeCauses()}" }
    null
  }
}

private val log = KetchLogger("FileAccessor")

private fun flags(resolver: ContentResolver, document: Uri): Int =
  resolver.query(document, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { cursor ->
    if (cursor.moveToFirst()) cursor.getInt(0) else 0
  } ?: 0

private fun displayName(resolver: ContentResolver, document: Uri): String? =
  resolver.query(document, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
    if (it.moveToFirst()) it.getString(0) else null
  }

/**
 * The document that [uri] creates files in: the document itself, such as a folder inside a
 * tree, or the root document of a tree.
 */
private fun parentDocument(uri: Uri): Uri = when {
  DocumentsContract.isDocumentUri(AndroidContext.get(), uri) -> uri
  DocumentsContract.isTreeUri(uri) ->
    DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
  else -> uri
}

/**
 * The folder named [name] in [parent], a folder of a tree, or `null` when there is none. Names
 * are compared ignoring case, as storage that ignores it would add ` (1)` to a new folder.
 */
private fun findChildFolder(resolver: ContentResolver, parent: Uri, name: String): Uri? {
  val children = DocumentsContract.buildChildDocumentsUriUsingTree(
    parent,
    DocumentsContract.getDocumentId(parent),
  )
  val columns = arrayOf(
    Document.COLUMN_DOCUMENT_ID,
    Document.COLUMN_DISPLAY_NAME,
    Document.COLUMN_MIME_TYPE,
  )
  resolver.query(children, columns, null, null, null)?.use { cursor ->
    while (cursor.moveToNext()) {
      if (cursor.getString(2) == Document.MIME_TYPE_DIR &&
        cursor.getString(1).equals(name, ignoreCase = true)
      ) {
        return DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
      }
    }
  }
  return null
}
