import SwiftUI
import KetchApp

@main
struct iOSApp: App {
  // Holds files opened in Ketch until the Compose UI takes them.
  private let incoming = IncomingDownloads()

  init() {
    // Before launch finishes, so a tapped notification that launches Ketch is handled.
    IosNotifier.shared.install()
  }

  var body: some Scene {
    WindowGroup {
      ContentView(incoming: incoming)
        // Receives .torrent files opened from Files, AirDrop and other apps.
        .onOpenURL { url in
          incoming.offerFile(url: url)
        }
    }
  }
}
