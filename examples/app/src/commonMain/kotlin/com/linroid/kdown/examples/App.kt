package com.linroid.kdown.examples

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.kdown.KDown

@Composable
fun App() {
  MaterialTheme {
    Surface(
      modifier = Modifier.fillMaxSize(),
      color = MaterialTheme.colorScheme.background
    ) {
      Column(
        modifier = Modifier.padding(16.dp)
      ) {
        Text(
          text = "KDown Examples",
          style = MaterialTheme.typography.headlineMedium
        )
        Text(
          text = "Version: ${KDown.VERSION}",
          style = MaterialTheme.typography.bodyMedium
        )
        // TODO: Add download UI demonstration
      }
    }
  }
}
