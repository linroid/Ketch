package com.linroid.ketch.app.ui.files

import com.linroid.ketch.app.components.KetchMenuScope
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.state.FileOrder
import com.linroid.ketch.app.state.FileSort
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.sort_reverse

/**
 * The items of a torrent file list's Sort menu: [orders], with [sort]'s checked, then Reverse
 * order. Picking another order starts it in its own direction; picking the checked one keeps it.
 */
internal fun KetchMenuScope.fileSortItems(
  sort: FileSort,
  orders: List<FileOrder>,
  onSort: (FileSort) -> Unit,
) {
  for (order in orders) {
    item(
      label = order.label,
      checked = order == sort.order,
      onClick = { if (order != sort.order) onSort(FileSort(order)) },
    )
  }
  divider()
  item(
    label = Res.string.sort_reverse.text(),
    checked = sort.reversed,
    onClick = { onSort(sort.reverse()) },
  )
}
