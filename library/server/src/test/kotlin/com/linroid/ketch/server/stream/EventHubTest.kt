package com.linroid.ketch.server.stream

import com.linroid.ketch.api.ControlKey
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStats
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.endpoints.model.TasksResponse
import com.linroid.ketch.endpoints.stream.InterestError
import com.linroid.ketch.endpoints.stream.InterestRef
import com.linroid.ketch.endpoints.stream.InterestUpdate
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamHello
import com.linroid.ketch.endpoints.stream.StreamInterest
import com.linroid.ketch.endpoints.stream.StreamSegments
import com.linroid.ketch.endpoints.stream.StreamSubscribed
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.endpoints.stream.StreamTaskRef
import com.linroid.ketch.endpoints.stream.TaskProgressUpdate
import com.linroid.ketch.endpoints.stream.TaskSegmentsUpdate
import com.linroid.ketch.endpoints.stream.TaskStateUpdate
import com.linroid.ketch.endpoints.stream.UnknownInterest
import com.linroid.ketch.torrent.api.TorrentActivity
import com.linroid.ketch.torrent.api.TorrentCapabilities
import com.linroid.ketch.torrent.api.TorrentCommandContext
import com.linroid.ketch.torrent.api.TorrentCommandError
import com.linroid.ketch.torrent.api.TorrentCommandException
import com.linroid.ketch.torrent.api.TorrentCommandResult
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentFileOrder
import com.linroid.ketch.torrent.api.TorrentFilePage
import com.linroid.ketch.torrent.api.TorrentInterest
import com.linroid.ketch.torrent.api.TorrentPageRequest
import com.linroid.ketch.torrent.api.TorrentRevision
import com.linroid.ketch.torrent.api.TorrentSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EventHubTest {
  private val json = Json { ignoreUnknownKeys = true }

  // -- Snapshot and lifecycle --

  @Test
  fun open_sendsHelloSubscribedAndSnapshot() = runTest {
    val ketch = FakeKetch(FakeTask("a"))
    val client = connect(hub(ketch))

    assertEquals(
      listOf(StreamEvents.HELLO, StreamEvents.SUBSCRIBED, StreamEvents.TASKS),
      client.names(),
    )
    assertEquals(client.stream.id, client.decode<StreamHello>(0).streamId)
    assertEquals(0, client.decode<StreamSubscribed>(1).revision)
    assertEquals(listOf("a"), client.decode<TasksResponse>(2).tasks.map { it.taskId })
  }

  @Test
  fun tasksAddedAndRemoved_followTheSnapshot() = runTest {
    val ketch = FakeKetch(FakeTask("a"))
    val client = connect(hub(ketch))
    client.clear()

    ketch.tasks.value = listOf(ketch.task("a"), FakeTask("b"))
    runCurrent()
    ketch.tasks.value = listOf(ketch.task("b"))
    runCurrent()

    assertEquals(listOf(StreamEvents.TASK_ADDED, StreamEvents.TASK_REMOVED), client.names())
    assertEquals("a", client.decode<StreamTaskRef>(1).taskId)
  }

  @Test
  fun progress_withoutRule_isOnlySentWithStateChanges() = runTest {
    val task = FakeTask("a", downloading(0))
    val ketch = FakeKetch(task)
    val client = connect(hub(ketch))
    client.clear()

    task.state.value = downloading(10)
    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(emptyList(), client.names())

    task.state.value = DownloadState.Paused(DownloadProgress(20, 100))
    runCurrent()
    val update = client.decode<TaskStateUpdate>(0)
    assertEquals(DownloadState.Paused(DownloadProgress(20, 100)), update.state)
  }

  @Test
  fun stateUpdate_carriesRequestOnlyWhenItChanged() = runTest {
    val task = FakeTask("a")
    val client = connect(hub(FakeKetch(task)))
    client.clear()

    task.queuePosition.value = 2
    runCurrent()
    task.requestState.value = task.request.copy(speedLimit = SpeedLimit.of(1024))
    runCurrent()

    val position = client.decode<TaskStateUpdate>(0)
    assertEquals(2, position.queuePosition)
    assertNull(position.request)
    assertEquals(task.request, client.decode<TaskStateUpdate>(1).request)
  }

  // -- Progress intervals --

  @Test
  fun progress_firstChangeAtOnce_thenLatestOncePerStep() = runTest {
    val task = FakeTask("a", downloading(0))
    val hub = hub(FakeKetch(task))
    val client = connect(hub)
    hub.subscribe(client.stream.id, null, subscription(progressMs = 400))
    runCurrent()
    client.clear()

    task.state.value = downloading(1)
    runCurrent()
    assertEquals(listOf(1L), client.progress())

    advanceTimeBy(100)
    task.state.value = downloading(2)
    advanceTimeBy(100)
    task.state.value = downloading(3)
    runCurrent()
    assertEquals(listOf(1L), client.progress())

    // 400 ms rounds up to the 500 ms step.
    advanceTimeBy(300)
    runCurrent()
    assertEquals(listOf(1L, 3L), client.progress())
  }

  @Test
  fun stateChange_isNotHeldByPendingProgress() = runTest {
    val task = FakeTask("a", downloading(0))
    val hub = hub(FakeKetch(task))
    val client = connect(hub)
    hub.subscribe(client.stream.id, null, subscription(progressMs = 5_000))
    runCurrent()
    task.state.value = downloading(1)
    runCurrent()
    client.clear()

    task.state.value = downloading(2)
    runCurrent()
    task.state.value = DownloadState.Paused(DownloadProgress(2, 100))
    runCurrent()
    assertEquals(listOf(StreamEvents.TASK_STATE), client.names())

    // The state carried the progress, so none is left to send.
    advanceTimeBy(5_000)
    runCurrent()
    assertEquals(listOf(StreamEvents.TASK_STATE), client.names())
  }

  @Test
  fun newlyAsked_progressAndSegmentsComeAtOnce() = runTest {
    val task = FakeTask("a", downloading(0))
    val hub = hub(FakeKetch(task))
    val client = connect(hub)
    client.clear()

    task.state.value = downloading(2)
    task.segments.value = listOf(Segment(0, 0, 99, 2))
    runCurrent()
    // Nobody asked for either.
    assertEquals(emptyList(), client.names())

    hub.subscribe(
      client.stream.id,
      null,
      subscription(progressMs = 10_000, segments = listOf(StreamSegments(setOf("a"), 10_000))),
    )
    runCurrent()

    assertEquals(
      listOf(StreamEvents.SUBSCRIBED, StreamEvents.TASK_PROGRESS, StreamEvents.TASK_SEGMENTS),
      client.names(),
    )
    assertEquals(2, client.decode<TaskProgressUpdate>(1).progress.downloadedBytes)
    assertEquals(task.segments.value, client.decode<TaskSegmentsUpdate>(2).segments)
  }

  @Test
  fun newlyAsked_changesRightAfterwardWaitTheirStep() = runTest {
    val task = FakeTask("a", downloading(0))
    val hub = hub(FakeKetch(task))
    val client = connect(hub)
    task.state.value = downloading(1)
    task.segments.value = listOf(Segment(0, 0, 99, 1))
    runCurrent()

    hub.subscribe(
      client.stream.id,
      null,
      subscription(progressMs = 1_000, segments = listOf(StreamSegments(setOf("a"), 1_000))),
    )
    runCurrent()
    client.clear()
    // The values sent with the subscription count as this interval's.
    task.state.value = downloading(2)
    task.segments.value = listOf(Segment(0, 0, 99, 2))
    advanceTimeBy(999)
    assertEquals(emptyList(), client.names())

    advanceTimeBy(1)
    runCurrent()
    assertEquals(listOf(2L), client.progress())
    assertEquals(2, client.decode<TaskSegmentsUpdate>(1).segments.single().downloadedBytes)
  }

  @Test
  fun slowClient_skipsIntermediateProgress() = runTest {
    val task = FakeTask("a", downloading(0))
    val hub = hub(FakeKetch(task))
    val stream = assertNotNull(hub.open(null))
    val gate = CompletableDeferred<Unit>()
    val events = mutableListOf<OutgoingEvent>()
    backgroundScope.launch {
      stream.run { event ->
        if (event.event == StreamEvents.TASK_PROGRESS) gate.await()
        events += event
      }
    }
    hub.subscribe(stream.id, null, subscription(progressMs = 200))
    runCurrent()
    task.state.value = downloading(1)
    runCurrent()

    // The writer is blocked on the first progress event meanwhile.
    for (bytes in 2L..20L) {
      task.state.value = downloading(bytes)
      advanceTimeBy(200)
    }
    gate.complete(Unit)
    advanceTimeBy(200)
    runCurrent()

    val sent = events.filter { it.event == StreamEvents.TASK_PROGRESS }
      .map { json.decodeFromString<TaskProgressUpdate>(it.data).progress.downloadedBytes }
    assertEquals(listOf(1L, 20L), sent)
  }

  @Test
  fun segments_onlyForNamedTasks() = runTest {
    val task = FakeTask("a")
    val other = FakeTask("b")
    val hub = hub(FakeKetch(task, other))
    val client = connect(hub)
    hub.subscribe(
      client.stream.id,
      null,
      subscription(segments = listOf(StreamSegments(setOf("a"), 1_000))),
    )
    runCurrent()
    client.clear()

    task.segments.value = listOf(Segment(0, 0, 99, 10))
    other.segments.value = listOf(Segment(0, 0, 99, 10))
    runCurrent()

    assertEquals(listOf(StreamEvents.TASK_SEGMENTS), client.names())
    assertEquals("a", client.decode<TaskSegmentsUpdate>(0).taskId)
  }

  @Test
  fun stats_sentEachIntervalOnlyWhenChanged() = runTest {
    val task = FakeTask("a", downloading(0, bps = 100))
    val hub = hub(FakeKetch(task))
    val client = connect(hub)
    val subscription = subscription(interests = listOf(follow(Interest.Stats, 1_000)))
    hub.subscribe(client.stream.id, null, subscription)
    runCurrent()
    assertEquals(100, client.value(Interest.Stats, client.names().lastIndex).downloadBps)
    client.clear()

    advanceTimeBy(3_000)
    runCurrent()
    assertEquals(emptyList(), client.names())

    task.state.value = downloading(50, bps = 300)
    advanceTimeBy(1_000)
    runCurrent()
    val stats = client.value(Interest.Stats, 0)
    assertEquals(300, stats.downloadBps)
    assertEquals(50, stats.remainingBytes)
  }

  @Test
  fun streams_shareEncodedPayloads() = runTest {
    val task = FakeTask("a")
    val hub = hub(FakeKetch(task))
    val first = connect(hub)
    val second = connect(hub)
    first.clear()
    second.clear()

    task.state.value = DownloadState.Paused(DownloadProgress(0, 100))
    runCurrent()

    assertSame(first.events.single().data, second.events.single().data)
  }

  // -- Subscriptions --

  @Test
  fun subscribe_staleRevision_changesNothing() = runTest {
    val hub = hub(FakeKetch())
    val client = connect(hub)
    val stats = subscription(revision = 2, interests = listOf(follow(Interest.Stats, 700)))
    hub.subscribe(client.stream.id, null, stats)
    runCurrent()
    client.clear()

    val answer = hub.subscribe(client.stream.id, null, subscription(revision = 1))
    runCurrent()

    assertEquals(2, answer.revision)
    assertEquals(listOf(follow(Interest.Stats, 700)), answer.subscription.interests)
    assertEquals(emptyList(), client.names())
  }

  @Test
  fun subscribe_otherOwnerOrClosedStream_isUnknown() = runTest {
    val hub = hub(FakeKetch())
    val client = connect(hub, owner = "token-a")

    assertFailsWith<UnknownStreamException> {
      hub.subscribe(client.stream.id, "token-b", subscription())
    }
    client.stream.close()
    assertFailsWith<UnknownStreamException> {
      hub.subscribe(client.stream.id, "token-a", subscription())
    }
  }

  @Test
  fun planOf_roundsIntervalsMergesSegmentsAndRejectsLimits() {
    val plan = planOf(
      subscription(
        progressMs = 1,
        segments = listOf(
          StreamSegments(setOf("a"), 400),
          StreamSegments(setOf("a", "b"), 2_000),
        ),
        interests = listOf(
          follow(Interest.Stats, 90_000, id = 1),
          follow(Interest.Connections(36), 300, id = 2),
          follow(UnknownInterest, 1_000, id = 3),
        ),
      ),
    )
    assertEquals(200, plan.progress.step("any"))
    assertEquals(500, plan.segments.step("a"))
    assertEquals(2_000, plan.segments.step("b"))
    assertNull(plan.segments.step("c"))
    assertEquals(
      mapOf(
        1L to PlannedInterest(Interest.Stats, 60_000),
        2L to PlannedInterest(Interest.Connections(36), 500),
        3L to PlannedInterest(UnknownInterest, 1_000),
      ),
      plan.interests,
    )

    assertFailsWith<IllegalArgumentException> { planOf(subscription(progressMs = 0)) }
    assertFailsWith<IllegalArgumentException> {
      val interests = List(StreamEvents.MAX_INTERESTS + 1) { follow(Interest.Stats, id = it + 1L) }
      planOf(subscription(interests = interests))
    }
    assertFailsWith<IllegalArgumentException> {
      val ids = List(StreamEvents.MAX_TASK_IDS + 1) { "t$it" }.toSet()
      planOf(subscription(segments = listOf(StreamSegments(ids, 1_000))))
    }
    assertFailsWith<IllegalArgumentException> {
      planOf(subscription(interests = listOf(follow(Interest.Stats), follow(TorrentInterest("t")))))
    }
  }

  // -- Streams and shared sources --

  @Test
  fun open_refusesBeyondMaxStreams_andTracksOnlyWhileOpen() = runTest {
    val hub = hub(FakeKetch(), maxStreams = 1)
    assertFalse(hub.isTracking)

    val client = connect(hub)
    assertTrue(hub.isTracking)
    assertNull(hub.open(null))

    client.stream.close()
    assertFalse(hub.isTracking)
    assertEquals(0, hub.openStreams)
  }

  @Test
  fun interests_sharedBetweenStreams_andStoppedWhenUnfollowed() = runTest {
    val controller = ScriptedController()
    val hub = hub(FakeKetch(torrents = controller))
    val first = connect(hub)
    val second = connect(hub)

    val torrent = subscription(interests = listOf(follow(TorrentInterest("t1"))))
    hub.subscribe(first.stream.id, null, torrent)
    hub.subscribe(second.stream.id, null, torrent)
    runCurrent()
    assertEquals(1, controller.observers)
    val interest = TorrentInterest("t1")
    assertEquals("t1", first.value(interest, first.names().lastIndex)?.taskId)
    // Each value is encoded once; the envelope carries each stream's own ID.
    assertEquals(first.events.last().data, second.events.last().data)

    hub.subscribe(first.stream.id, null, subscription(revision = 2))
    second.stream.close()
    runCurrent()
    assertEquals(emptySet(), hub.followedInterests)
    assertEquals(0, controller.observers)
  }

  @Test
  fun interest_latestValueOncePerStep_andItsEndNeverWaits() = runTest {
    val controller = ScriptedController()
    val hub = hub(FakeKetch(torrents = controller))
    val client = connect(hub)
    val interest = TorrentInterest("live")
    hub.subscribe(client.stream.id, null, subscription(interests = listOf(follow(interest, 1_000))))
    runCurrent()
    assertEquals(listOf(1L), client.revisions(interest))

    controller.live.value = controller.snapshotOf("live", 2)
    advanceTimeBy(100)
    controller.live.value = controller.snapshotOf("live", 3)
    runCurrent()
    assertEquals(listOf(1L), client.revisions(interest))

    advanceTimeBy(900)
    runCurrent()
    assertEquals(listOf(1L, 3L), client.revisions(interest))

    // Held back by the interval, the last value still goes out with the end, at once.
    controller.live.value = controller.snapshotOf("live", 4)
    runCurrent()
    controller.live.value = null
    runCurrent()
    val tail = client.events.takeLast(2)
    assertEquals(listOf(StreamEvents.INTEREST, StreamEvents.INTEREST_END), tail.map { it.event })
    assertEquals(JsonNull, json.decodeFromString<InterestUpdate>(tail[0].data).value)
    assertEquals(1, json.decodeFromString<InterestRef>(tail[1].data).id)
  }

  @Test
  fun interest_endsAndFailures_areInterestEvents() = runTest {
    val hub = hub(FakeKetch(torrents = ScriptedController()))
    val client = connect(hub)
    client.clear()

    val torrents = listOf(
      follow(TorrentInterest("gone"), id = 1),
      follow(TorrentInterest("busy"), id = 2),
    )
    hub.subscribe(client.stream.id, null, subscription(interests = torrents))
    runCurrent()

    val update = client.decode<InterestUpdate>(client.names().indexOf(StreamEvents.INTEREST))
    assertEquals(1, update.id)
    assertEquals(JsonNull, update.value)
    val end = client.decode<InterestRef>(client.names().indexOf(StreamEvents.INTEREST_END))
    assertEquals(1, end.id)
    val failure = client.decode<InterestError>(client.names().indexOf(StreamEvents.INTEREST_ERROR))
    assertEquals(2, failure.id)
    assertEquals(TorrentCommandError.RESOURCE_EXHAUSTED.wireName, failure.error)
  }

  @Test
  fun interestsWithoutSupport_failWithoutEndingTheStream() = runTest {
    val task = FakeTask("a")
    val hub = hub(FakeKetch(task))
    val client = connect(hub)
    client.clear()

    val interests = listOf(
      follow(TorrentInterest("t1"), id = 1),
      follow(Interest.Connections(10), id = 2),
      follow(UnknownInterest, id = 3),
    )
    hub.subscribe(client.stream.id, null, subscription(interests = interests))
    runCurrent()
    val errors = client.events.filter { it.event == StreamEvents.INTEREST_ERROR }
      .map { json.decodeFromString<InterestError>(it.data) }
    assertEquals(setOf(1L, 2L, 3L), errors.map { it.id }.toSet())
    assertTrue(errors.all { it.error == StreamEvents.UNSUPPORTED })

    task.queuePosition.value = 1
    runCurrent()
    assertEquals(StreamEvents.TASK_STATE, client.names().last())
  }

  @Test
  fun droppedInterest_sendsNothingAfterSubscribed() = runTest {
    val controller = ScriptedController()
    val hub = hub(FakeKetch(torrents = controller))
    val client = connect(hub)
    val interest = TorrentInterest("live")
    hub.subscribe(client.stream.id, null, subscription(interests = listOf(follow(interest))))
    runCurrent()

    hub.subscribe(client.stream.id, null, subscription(revision = 2))
    controller.live.value = controller.snapshotOf("live", 2)
    runCurrent()

    assertEquals(StreamEvents.SUBSCRIBED, client.names().last())
    assertEquals(listOf(1L), client.revisions(interest))
  }

  // -- Harness --

  private fun TestScope.hub(ketch: KetchApi, maxStreams: Int = MAX_EVENT_STREAMS) =
    EventHub(ketch, backgroundScope, testScheduler.timeSource, maxStreams)

  private fun TestScope.connect(hub: EventHub, owner: String? = null): Client {
    val client = Client(assertNotNull(hub.open(owner)))
    backgroundScope.launch { client.stream.run { client.events += it } }
    runCurrent()
    return client
  }

  private inner class Client(val stream: EventStream) {
    val events = mutableListOf<OutgoingEvent>()

    fun names() = events.map { it.event }

    fun clear() = events.clear()

    inline fun <reified T> decode(index: Int): T =
      json.decodeFromString<T>(events[index].data)

    /** The value of [interest] the `interest` event at [index] carries. */
    fun <T> value(interest: Interest<T>, index: Int): T =
      json.decodeFromJsonElement(interest.valueSerializer, decode<InterestUpdate>(index).value)

    /** The revisions of the torrent snapshots sent for [interest]. */
    fun revisions(interest: TorrentInterest): List<Long> =
      events.indices.filter { events[it].event == StreamEvents.INTEREST }
        .mapNotNull { value(interest, it)?.revision?.sequence }

    fun progress(taskId: String = "a"): List<Long> =
      events.filter { it.event == StreamEvents.TASK_PROGRESS }
        .map { json.decodeFromString<TaskProgressUpdate>(it.data) }
        .filter { it.taskId == taskId }
        .map { it.progress.downloadedBytes }
  }

  private fun subscription(
    revision: Long = 1,
    progressMs: Long? = null,
    segments: List<StreamSegments> = emptyList(),
    interests: List<StreamInterest> = emptyList(),
  ) = StreamSubscription(revision, progressMs, segments, interests)

  private fun follow(interest: Interest<*>, intervalMs: Long = 200, id: Long = 1) =
    StreamInterest(id, interest, intervalMs)

  private fun downloading(bytes: Long, bps: Long = 0) =
    DownloadState.Downloading(DownloadProgress(bytes, 100, bps))

  private class FakeTask(
    override val taskId: String,
    state: DownloadState = DownloadState.Queued,
  ) : DownloadTask {
    override val requestState = MutableStateFlow(DownloadRequest("https://example.com/$taskId"))
    override val request get() = requestState.value
    override val createdAt = Instant.fromEpochMilliseconds(0)
    override val state = MutableStateFlow(state)
    override val segments = MutableStateFlow<List<Segment>>(emptyList())
    override val queuePosition = MutableStateFlow<Int?>(null)
    override suspend fun pause() = error("Unexpected call")
    override suspend fun resume(destination: Destination?) = error("Unexpected call")
    override suspend fun cancel() = error("Unexpected call")
    override suspend fun remove(deleteFiles: Boolean) = error("Unexpected call")
    override suspend fun setSpeedLimit(limit: SpeedLimit) = error("Unexpected call")
    override suspend fun setPriority(priority: DownloadPriority) = error("Unexpected call")
    override suspend fun setConnections(connections: Int) = error("Unexpected call")
    override suspend fun reschedule(
      schedule: DownloadSchedule,
      conditions: List<DownloadCondition>,
    ) = error("Unexpected call")
  }

  /**
   * An engine with [initial] tasks, their stats, the torrent controls given, and no live
   * connections.
   */
  private class FakeKetch(
    vararg initial: DownloadTask,
    private val torrents: ScriptedController? = null,
  ) : KetchApi {
    override val backendLabel = "Fake"
    override val tasks = MutableStateFlow(initial.toList())

    fun task(taskId: String) = tasks.value.first { it.taskId == taskId }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> controller(key: ControlKey<T>): T? =
      if (key === TorrentController.Key) torrents as T? else null

    @Suppress("UNCHECKED_CAST")
    override fun <T> subscribe(interest: Interest<T>, interval: Duration): Flow<T> = when {
      interest == Interest.Stats -> flow {
        while (true) {
          emit(KetchStats.of(tasks.value.map { it.state.value }))
          delay(interval)
        }
      }.distinctUntilChanged() as Flow<T>
      interest is TorrentInterest && torrents != null ->
        torrents.observe(interest.taskId) as Flow<T>
      else -> flow { throw UnsupportedOperationException("Not followed in tests") }
    }

    override suspend fun download(request: DownloadRequest) = error("Unexpected call")
    override suspend fun resolve(input: ResolveInput): ResolvedSource =
      error("Unexpected call")
    override suspend fun start() = error("Unexpected call")
    override suspend fun status(): KetchStatus = error("Unexpected call")
    override suspend fun updateConfig(config: DownloadConfig) = error("Unexpected call")
    override fun close() {}
  }

  /**
   * One torrent, `t1`; `live` reports [live] until it is `null`, `gone` is removed after a
   * snapshot and `busy` refuses observers.
   */
  private class ScriptedController : TorrentController {
    var observers = 0
    val live = MutableStateFlow<TorrentSnapshot?>(snapshotOf("live", 1))

    fun observe(taskId: String): Flow<TorrentSnapshot?> = flow {
      when (taskId) {
        "busy" -> throw TorrentCommandException(
          TorrentCommandError.RESOURCE_EXHAUSTED,
          "Too many torrent subscriptions",
        )
        "gone" -> {
          emit(snapshotOf(taskId))
          emit(null)
        }
        "live" -> emitAll(live.transformWhile { emit(it); it != null })
        else -> {
          emit(snapshotOf(taskId))
          awaitCancellation()
        }
      }
    }.onStart { observers++ }.onCompletion { observers-- }

    override suspend fun capabilities(): TorrentCapabilities = error("Unexpected call")
    override suspend fun snapshot(taskId: String): TorrentSnapshot? = error("Unexpected call")
    override suspend fun files(
      taskId: String,
      page: TorrentPageRequest,
      order: TorrentFileOrder,
      descending: Boolean,
    ): TorrentFilePage? = error("Unexpected call")
    override suspend fun select(
      taskId: String,
      fileIds: Set<String>,
      context: TorrentCommandContext,
    ): TorrentCommandResult = error("Unexpected call")
    override suspend fun setSeeding(
      taskId: String,
      seeding: Boolean,
      context: TorrentCommandContext,
    ): TorrentCommandResult = error("Unexpected call")

    fun snapshotOf(taskId: String, revision: Long = 1) = TorrentSnapshot(
      taskId = taskId,
      revision = TorrentRevision("epoch", revision),
      activity = TorrentActivity.DOWNLOADING,
      selectionGeneration = 1,
      selectionComplete = false,
    )
  }
}
