import BackgroundTasks
import SwiftUI
import KetchApp

@main
struct iOSApp: App {
  // Holds files and links opened in Ketch until the Compose UI takes them.
  private let incoming = IncomingDownloads()

  init() {
    // Before launch finishes, so a tapped notification that launches Ketch is handled.
    IosNotifier.shared.install()
    // Downloads started in Ketch can go on in the background, with their progress shown by iOS.
    // Older versions pause them as Ketch leaves the screen.
    if #available(iOS 26.0, *) {
      KetchBackground.shared.continuedProcessing = ContinuedDownloads()
    }
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

/// Keeps Ketch's downloads running in the background as a continued processing task, whose
/// title and progress iOS shows, until they finish or iOS needs the time back. Ketch begins it
/// on the main thread while it is in front, as downloads start.
@available(iOS 26.0, *)
final class ContinuedDownloads: NSObject, ContinuedProcessing {
  // Each request gets an identifier of its own under the prefix that Ketch-Info.plist permits.
  private let prefix = (Bundle.main.bundleIdentifier ?? "Ketch") + ".downloads."
  private var task: BGContinuedProcessingTask?
  private var latest: BackgroundProgress?

  func begin(progress: BackgroundProgress) -> Bool {
    latest = progress
    let identifier = prefix + UUID().uuidString
    let registered = BGTaskScheduler.shared.register(
      forTaskWithIdentifier: identifier,
      using: .main
    ) { [weak self] task in
      guard let self, let task = task as? BGContinuedProcessingTask else {
        task.setTaskCompleted(success: false)
        return
      }
      self.run(task)
    }
    guard registered else { return false }
    let request = BGContinuedProcessingTaskRequest(
      identifier: identifier,
      title: progress.title,
      subtitle: progress.subtitle
    )
    // When iOS cannot start it now, the downloads pause in the background instead.
    request.strategy = .fail
    do {
      try BGTaskScheduler.shared.submit(request)
      return true
    } catch {
      latest = nil
      return false
    }
  }

  func update(progress: BackgroundProgress) {
    latest = progress
    if let task {
      show(progress, on: task)
    }
  }

  func end(success: Bool) {
    latest = nil
    task?.setTaskCompleted(success: success)
    task = nil
  }

  private func run(_ task: BGContinuedProcessingTask) {
    // Ketch ended the work before iOS started it.
    guard let latest else {
      task.setTaskCompleted(success: true)
      return
    }
    self.task = task
    task.expirationHandler = { [weak self] in
      DispatchQueue.main.async {
        self?.expire(task)
      }
    }
    show(latest, on: task)
  }

  private func expire(_ task: BGContinuedProcessingTask) {
    guard self.task === task else { return }
    self.task = nil
    latest = nil
    KetchBackground.shared.continuedProcessingExpired()
    task.setTaskCompleted(success: false)
  }

  private func show(_ progress: BackgroundProgress, on task: BGContinuedProcessingTask) {
    task.updateTitle(progress.title, subtitle: progress.subtitle)
    if progress.permille >= 0 {
      task.progress.totalUnitCount = 1000
      task.progress.completedUnitCount = Int64(progress.permille)
    } else {
      // Indeterminate while a size is unknown or every download waits.
      task.progress.totalUnitCount = -1
    }
  }
}
