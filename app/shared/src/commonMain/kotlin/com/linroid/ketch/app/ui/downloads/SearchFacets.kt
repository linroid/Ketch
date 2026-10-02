package com.linroid.ketch.app.ui.downloads

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.SearchQuery
import com.linroid.ketch.app.util.SearchToken

/**
 * The 32 dp row under the tabs while searching: the search's tokens as removable chips, the
 * Type, Site, Device (with rows of several devices) and Origin menus that add more, and how
 * many downloads match, such as "12 of 340".
 *
 * @param rows the downloads on the tab before the search, which the menus offer values from.
 * @param onQueryChange replaces the search with a changed query.
 */
@Composable
internal fun FacetRow(
  query: SearchQuery,
  rows: List<TaskRow>,
  matched: Int,
  total: Int,
  onQueryChange: (SearchQuery) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier
      .fillMaxWidth()
      .height(spacing.s8)
      .padding(horizontal = spacing.pageHeaderPadding),
  ) {
    val scroll = rememberScrollState()
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.weight(1f).scrollFade(scroll).horizontalScroll(scroll),
    ) {
      for (token in query.tokens) {
        KetchChip(
          label = token.label,
          selected = true,
          onClick = { onQueryChange(query - token) },
          onRemove = { onQueryChange(query - token) },
        )
      }
      Facet(
        label = "Type",
        options = rows.groupingBy { it.fileType }.eachCount().entries
          .sortedBy { it.key.ordinal }
          .map { (type, count) -> FacetOption(SearchToken.Type(type), type.label, count) },
        query = query,
        onQueryChange = onQueryChange,
      )
      Facet(
        label = "Site",
        options = rows.mapNotNull { it.sourceHost }.groupingBy { it }.eachCount().entries
          .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
          .take(MAX_SITES)
          .map { (host, count) -> FacetOption(SearchToken.Host(host), host, count) },
        query = query,
        onQueryChange = onQueryChange,
      )
      val devices = rows.groupingBy { it.device.name }.eachCount()
      if (devices.size > 1) {
        Facet(
          label = "Device",
          options = devices.entries.map { (name, count) ->
            FacetOption(SearchToken.Device(name), name, count)
          },
          query = query,
          onQueryChange = onQueryChange,
        )
      }
      Facet(
        label = "Origin",
        options = rows.mapNotNull { it.origin }.groupingBy { it }.eachCount().entries
          .sortedBy { it.key.ordinal }
          .map { (origin, count) -> FacetOption(SearchToken.Origin(origin), origin.label, count) },
        query = query,
        onQueryChange = onQueryChange,
      )
    }
    Text(
      text = "$matched of $total",
      style = KetchTheme.typography.numeralS,
      color = colors.textTertiary,
      maxLines = 1,
    )
  }
}

/**
 * A value a facet menu offers.
 *
 * @property count downloads on the tab with this value.
 */
private class FacetOption(val token: SearchToken, val label: String, val count: Int)

/**
 * A chip that opens a menu of [options]; picking one adds its token or removes it again. A facet
 * without options is left out.
 */
@Composable
private fun Facet(
  label: String,
  options: List<FacetOption>,
  query: SearchQuery,
  onQueryChange: (SearchQuery) -> Unit,
) {
  var open by remember { mutableStateOf(false) }
  if (options.isEmpty()) return
  Box {
    KetchChip(
      label = label,
      selected = false,
      onClick = { open = true },
      trailingIcon = KetchIcon.ChevronDown,
    )
    KetchMenu(expanded = open, onDismissRequest = { open = false }) {
      for (option in options) {
        val active = option.token in query.tokens
        item(
          label = option.label,
          shortcut = option.count.toString(),
          checked = active,
          onClick = { onQueryChange(if (active) query - option.token else query + option.token) },
        )
      }
    }
  }
}

private const val MAX_SITES = 12
