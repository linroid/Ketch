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
    ComposeView(incoming: incoming)
      .ignoresSafeArea(.keyboard)
  }
}
