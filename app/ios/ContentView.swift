import UIKit
import SwiftUI
import KetchApp

struct ComposeView: UIViewControllerRepresentable {
  let incoming: IncomingDownloads

  func makeUIViewController(context: Context) -> UIViewController {
    MainViewControllerKt.MainViewController(incoming: incoming)
  }

  func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
  }
}

struct ContentView: View {
  let incoming: IncomingDownloads

  var body: some View {
    // Compose pads for the safe area itself (WindowInsets.safeDrawing); inside SwiftUI's safe
    // area it would get the bars' height twice, over a blank band where the bars are.
    ComposeView(incoming: incoming)
      .ignoresSafeArea()
  }
}
