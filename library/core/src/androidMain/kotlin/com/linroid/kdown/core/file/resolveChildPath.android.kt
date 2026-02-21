@file:Suppress("UseKtx")

package com.linroid.kdown.core.file

import android.net.Uri
import android.provider.DocumentsContract
import com.linroid.kdown.core.AndroidContext
import kotlinx.io.files.Path

internal actual fun resolveChildPath(
  directory: String,
  fileName: String,
): String {
  val uri = Uri.parse(directory)
  if (uri.scheme == "content") {
    val parentDocUri = if (DocumentsContract.isTreeUri(uri)) {
      DocumentsContract.buildDocumentUriUsingTree(
        uri,
        DocumentsContract.getTreeDocumentId(uri),
      )
    } else {
      uri
    }
    val docUri = DocumentsContract.createDocument(
      AndroidContext.get().contentResolver,
      parentDocUri,
      "application/octet-stream",
      fileName,
    ) ?: throw IllegalStateException(
      "Failed to create document '$fileName' in $directory"
    )
    return docUri.toString()
  }
  return Path(directory, fileName).toString()
}
