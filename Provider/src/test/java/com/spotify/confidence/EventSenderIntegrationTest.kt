package com.spotify.confidence

import android.content.Context
import com.spotify.confidence.client.SdkMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files

private const val clientSecret = "WciJVLIEiNnRxV8gaYPZNCFF8vbAXOu6"
private val mockContext: Context = mock()

@OptIn(ExperimentalCoroutinesApi::class)
class EventSenderIntegrationTest {
    private var eventSender: EventSender? = null

    private val directory = Files.createTempDirectory("tmpTests").toFile()

    @Before
    fun setup() {
        whenever(mockContext.getDir("events", Context.MODE_PRIVATE)).thenReturn(directory)
        eventSender = null
        for (file in directory.walkFiles()) {
            file.delete()
        }
    }

    @Test
    fun emitting_an_event_writes_to_file() = runTest {
        val eventStorage = EventStorageImpl(mockContext)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        eventSender = ConfidenceFactory.create(
            mockContext,
            clientSecret,
            dispatcher = testDispatcher
        )
        val eventSender = this@EventSenderIntegrationTest.eventSender
        val eventCount = 4
        requireNotNull(eventSender)
        repeat(eventCount) {
            eventSender.track("navigate")
        }
        val list = mutableListOf<File>()
        for (file in directory.walkFiles()) {
            list.add(file)
        }
        Assert.assertTrue(list.size == 1)
        advanceUntilIdle()
        runBlocking {
            val events = eventStorage.eventsFor(list.first())
            Assert.assertTrue(events.size == eventCount)
        }
    }

    @Test
    fun emitting_an_event_writes_to_file_in_batches() = runTest {
        val eventStorage = EventStorageImpl(mockContext)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val batchSize = 10
        val flushPolicy = object : FlushPolicy {
            private var size = 0
            override fun reset() {
                size = 0
            }

            override fun hit(event: Event) {
                size++
            }

            override fun shouldFlush(): Boolean {
                return size >= batchSize
            }
        }
        val uploader = object : EventSenderUploader {
            override suspend fun upload(events: EventBatchRequest): Boolean {
                return false
            }
        }
        val engine = EventSenderEngineImpl(
            eventStorage,
            clientSecret,
            flushPolicies = listOf(flushPolicy),
            dispatcher = testDispatcher,
            sdkMetadata = SdkMetadata("kotlin_test", ""),
            uploader = uploader
        )
        eventSender = Confidence(
            eventSenderEngine = engine,
            dispatcher = testDispatcher,
            diskStorage = mock(),
            clientSecret = "",
            flagApplierClient = mock(),
            flagResolver = mock()
        )
        val eventSender = this@EventSenderIntegrationTest.eventSender
        val eventCount = 4 * batchSize + 2
        requireNotNull(eventSender)
        repeat(eventCount) {
            eventSender.track("navigate")
        }
        advanceUntilIdle()
        runBlocking {
            val batchReadyFiles = eventStorage.batchReadyFiles()
            val totalFiles = directory.walkFiles()
            Assert.assertEquals(4, batchReadyFiles.size)
            Assert.assertEquals(totalFiles.iterator().asSequence().toList().size, 5)
            for (file in batchReadyFiles) {
                Assert.assertEquals(eventStorage.eventsFor(file).size, batchSize)
            }

            val currentFile = directory
                .walkFiles()
                .filter { !it.name.endsWith("ready") }
                .first()
            Assert.assertEquals(eventStorage.eventsFor(currentFile).size, 2)
        }
    }

    @Test
    fun handles_message_key_collision() = runTest {
        val eventStorage = EventStorageImpl(mockContext)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val batchSize = 1
        val uploadedEvents: MutableList<Event> = mutableListOf()
        val flushPolicy = object : FlushPolicy {
            private var size = 0
            override fun reset() {
                size = 0
            }

            override fun hit(event: Event) {
                size++
            }

            override fun shouldFlush(): Boolean {
                return size >= batchSize
            }
        }
        val uploader = object : EventSenderUploader {
            override suspend fun upload(events: EventBatchRequest): Boolean {
                uploadedEvents.addAll(events.events)
                return false
            }
        }
        val engine = EventSenderEngineImpl(
            eventStorage,
            clientSecret,
            flushPolicies = listOf(flushPolicy),
            dispatcher = testDispatcher,
            sdkMetadata = SdkMetadata("kotlin_test", ""),
            uploader = uploader
        )
        engine.emit(
            eventName = "my_event",
            message = mapOf(
                "a" to ConfidenceValue.Integer(0),
                "message" to ConfidenceValue.Integer(1)
            ),
            context = mapOf(
                "a" to ConfidenceValue.Integer(2),
                "message" to ConfidenceValue.Integer(3)
            )
        )
        advanceUntilIdle()
        Assert.assertEquals("eventDefinitions/my_event", uploadedEvents[0].eventDefinition)
        Assert.assertEquals(
            mapOf(
                "message" to ConfidenceValue.Struct(
                    mapOf(
                        "a" to ConfidenceValue.Integer(0),
                        "message" to ConfidenceValue.Integer(1)
                    )
                ),
                "a" to ConfidenceValue.Integer(2)
            ),
            uploadedEvents[0].payload
        )
        print(uploadedEvents)
    }

    @Test
    fun emitting_an_event_batches_all_batches_sent_cleaned_up() = runTest {
        val eventStorage = EventStorageImpl(mockContext)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val batchSize = 10
        val flushPolicy = object : FlushPolicy {
            private var size = 0
            override fun reset() {
                size = 0
            }

            override fun hit(event: Event) {
                size++
            }

            override fun shouldFlush(): Boolean {
                return size >= batchSize
            }
        }
        var uploadRequestCount = 0
        val uploader = object : EventSenderUploader {
            override suspend fun upload(events: EventBatchRequest): Boolean {
                uploadRequestCount++
                return true
            }
        }
        val engine = EventSenderEngineImpl(
            eventStorage,
            clientSecret,
            flushPolicies = listOf(flushPolicy),
            dispatcher = testDispatcher,
            sdkMetadata = SdkMetadata("kotlin_test", ""),
            uploader = uploader
        )
        eventSender = Confidence(
            eventSenderEngine = engine,
            dispatcher = testDispatcher,
            diskStorage = mock(),
            clientSecret = "",
            flagApplierClient = mock(),
            flagResolver = mock()
        )
        val eventSender = this@EventSenderIntegrationTest.eventSender
        val eventCount = 4 * batchSize + 2
        requireNotNull(eventSender)
        repeat(eventCount) {
            eventSender.track("navigate")
        }
        advanceUntilIdle()
        Assert.assertEquals(uploadRequestCount, eventCount / batchSize)
        runBlocking {
            val batchReadyFiles = eventStorage.batchReadyFiles()
            val totalFiles = directory.walkFiles()
            // all files are sent and cleaned up
            Assert.assertEquals(batchReadyFiles.size, 0)
            // only current file exists
            Assert.assertEquals(totalFiles.iterator().asSequence().toList().size, 1)

            val currentFile = directory
                .walkFiles()
                .filter { !it.name.endsWith("ready") }
                .first()
            Assert.assertEquals(eventStorage.eventsFor(currentFile).size, 2)
        }
    }
}