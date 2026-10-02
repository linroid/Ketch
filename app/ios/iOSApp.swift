import SwiftUI
import KetchApp

@main
struct iOSApp: App {
  // Holds files and links opened in Ketch until the Compose UI takes them.
  private let incoming = IncomingDownloads()

  init() {
    // Before launch finishes, so a tapped notification that launches Ketch is handled.
    IosNotifier.shared.install()
  }

  var body: some Scene {
    WindowGroup {
      ContentView(incoming: incoming)
        // Receives .torrent files opened from Files, AirDrop and other apps, magnet links and
        // ketch://pair links from scanned pairing codes.
        .onOpenURL { url in
          _ = incoming.offerUrl(url: url)
        }
    }
  }
}
