@file:OptIn(
    ExperimentalCoroutinesApi::class,
    ExperimentalCoroutinesApi::class,
    ExperimentalCoroutinesApi::class,
    ExperimentalCoroutinesApi::class,
    ExperimentalCoroutinesApi::class,
    ExperimentalCoroutinesApi::class
)

package com.spotify.confidence

import android.content.Context
import com.spotify.confidence.apply.EventStatus
import com.spotify.confidence.apply.FlagsAppliedMap
import com.spotify.confidence.cache.APPLY_FILE_NAME
import com.spotify.confidence.cache.InMemoryCache
import com.spotify.confidence.cache.json
import com.spotify.confidence.cache.toCacheData
import com.spotify.confidence.client.AppliedFlag
import com.spotify.confidence.client.ConfidenceClient
import com.spotify.confidence.client.Flags
import com.spotify.confidence.client.ResolveFlags
import com.spotify.confidence.client.ResolveReason
import com.spotify.confidence.client.ResolveResponse
import com.spotify.confidence.client.ResolvedFlag
import com.spotify.confidence.client.Result
import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.ImmutableStructure
import dev.openfeature.sdk.Reason
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.events.EventHandler
import dev.openfeature.sdk.exceptions.ErrorCode
import dev.openfeature.sdk.exceptions.OpenFeatureError.FlagNotFoundError
import dev.openfeature.sdk.exceptions.OpenFeatureError.ParseError
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.nio.file.Files
import java.time.Instant

private const val cacheFileData = "{\n" +
    "  \"token1\": {\n" +
    "    \"test-kotlin-flag-0\": {\n" +
    "      \"time\": \"2023-06-26T11:55:33.443Z\",\n" +
    "      \"eventStatus\": \"SENT\"\n" +
    "    }\n" +
    "  },\n" +
    "  \"token2\": {\n" +
    "    \"test-kotlin-flag-2\": {\n" +
    "      \"time\": \"2023-06-26T11:55:33.444Z\",\n" +
    "      \"eventStatus\": \"SENT\"\n" +
    "    },\n" +
    "    \"test-kotlin-flag-3\": {\n" +
    "      \"time\": \"2023-06-26T11:55:33.445Z\",\n" +
    "      \"eventStatus\": \"CREATED\"\n" +
    "    }\n" +
    "  },\n" +
    "  \"token3\": {\n" +
    "    \"test-kotlin-flag-4\": {\n" +
    "      \"time\": \"2023-06-26T11:55:33.446Z\",\n" +
    "      \"eventStatus\": \"CREATED\"\n" +
    "    }\n" +
    "  }\n" +
    "}\n"

@OptIn(ExperimentalCoroutinesApi::class)
internal class ConfidenceFeatureProviderTests {
    private val mockClient: ConfidenceClient = mock()
    private val mockContext: Context = mock()
    private val instant = Instant.parse("2023-03-01T14:01:46.645Z")
    private val blueStringValues = mutableMapOf(
        "mystring" to Value.String("blue")
    )
    private val resolvedValueAsMap = mutableMapOf(
        "mystring" to Value.String("red"),
        "myboolean" to Value.Boolean(false),
        "myinteger" to Value.Integer(7),
        "mydouble" to Value.Double(3.14),
        "mydate" to Value.String(instant.toString()),
        "mystruct" to Value.Structure(
            mapOf(
                "innerString" to Value.String("innerValue")
            )
        ),
        "mynull" to Value.Null
    )
    private val resolvedFlags = Flags(
        listOf(
            ResolvedFlag(
                "test-kotlin-flag-1",
                "flags/test-kotlin-flag-1/variants/variant-1",
                ImmutableStructure(resolvedValueAsMap),
                ResolveReason.RESOLVE_REASON_MATCH
            )
        )
    )

    @Before
    fun setup() {
        whenever(mockContext.filesDir).thenReturn(Files.createTempDirectory("tmpTests").toFile())
    }

    @Test
    fun testMatching() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            client = mockClient,
            eventHandler = eventHandler,
            dispatcher = testDispatcher
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )
        confidenceFeatureProvider.initialize(ImmutableContext("foo"))
        advanceUntilIdle()
        verify(mockClient, times(1)).resolve(any(), eq(ImmutableContext("foo")))
        val evalString = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "default",
            ImmutableContext("foo")
        )
        val evalBool = confidenceFeatureProvider.getBooleanEvaluation(
            "test-kotlin-flag-1.myboolean",
            true,
            ImmutableContext("foo")
        )
        val evalInteger = confidenceFeatureProvider.getIntegerEvaluation(
            "test-kotlin-flag-1.myinteger",
            1,
            ImmutableContext("foo")
        )
        val evalDouble = confidenceFeatureProvider.getDoubleEvaluation(
            "test-kotlin-flag-1.mydouble",
            7.28,
            ImmutableContext("foo")
        )
        val evalDate = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mydate",
            "error",
            ImmutableContext("foo")
        )
        val evalObject = confidenceFeatureProvider.getObjectEvaluation(
            "test-kotlin-flag-1.mystruct",
            Value.Structure(mapOf()),
            ImmutableContext("foo")
        )
        val evalNested = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystruct.innerString",
            "error",
            ImmutableContext("foo")
        )
        val evalNull = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mynull",
            "error",
            ImmutableContext("foo")
        )

        advanceUntilIdle()
        verify(mockClient, times(1)).apply(any(), eq("token1"))

        assertEquals("red", evalString.value)
        assertEquals(false, evalBool.value)
        assertEquals(7, evalInteger.value)
        assertEquals(3.14, evalDouble.value)
        assertEquals("2023-03-01T14:01:46.645Z", evalDate.value)
        assertEquals(
            Value.Structure(mapOf("innerString" to Value.String("innerValue"))),
            evalObject.value
        )
        assertEquals("innerValue", evalNested.value)
        assertEquals("error", evalNull.value)

        assertEquals(Reason.TARGETING_MATCH.toString(), evalString.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalBool.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalInteger.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalDouble.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalDate.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalObject.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalNested.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalNull.reason)

        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalString.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalBool.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalInteger.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalDouble.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalDate.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalObject.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalNested.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalNull.variant)

        assertNull(evalString.errorMessage)
        assertNull(evalBool.errorMessage)
        assertNull(evalInteger.errorMessage)
        assertNull(evalDouble.errorMessage)
        assertNull(evalDate.errorMessage)
        assertNull(evalObject.errorMessage)
        assertNull(evalNested.errorMessage)
        assertNull(evalNull.errorMessage)

        assertNull(evalString.errorCode)
        assertNull(evalBool.errorCode)
        assertNull(evalInteger.errorCode)
        assertNull(evalDouble.errorCode)
        assertNull(evalDate.errorCode)
        assertNull(evalObject.errorCode)
        assertNull(evalNested.errorCode)
        assertNull(evalNull.errorCode)
    }

    @Test
    fun testDelayedApply() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            eventHandler = eventHandler,
            client = mockClient,
            dispatcher = testDispatcher
        )
        val cacheFile = File(mockContext.filesDir, APPLY_FILE_NAME)
        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Failure)

        val evaluationContext = ImmutableContext("foo")
        confidenceFeatureProvider.initialize(evaluationContext)
        advanceUntilIdle()

        verify(mockClient, times(1)).resolve(any(), eq(evaluationContext))

        val evalString = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "default",
            evaluationContext
        )
        val evalBool = confidenceFeatureProvider.getBooleanEvaluation(
            "test-kotlin-flag-1.myboolean",
            true,
            evaluationContext
        )
        val evalInteger = confidenceFeatureProvider.getIntegerEvaluation(
            "test-kotlin-flag-1.myinteger",
            1,
            evaluationContext
        )
        val evalDouble = confidenceFeatureProvider.getDoubleEvaluation(
            "test-kotlin-flag-1.mydouble",
            7.28,
            evaluationContext
        )
        val evalDate = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mydate",
            "error",
            evaluationContext
        )
        val evalObject = confidenceFeatureProvider.getObjectEvaluation(
            "test-kotlin-flag-1.mystruct",
            Value.Structure(mapOf()),
            evaluationContext
        )
        val evalNested = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystruct.innerString",
            "error",
            evaluationContext
        )
        val evalNull = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mynull",
            "error",
            evaluationContext
        )

        advanceUntilIdle()
        verify(mockClient, times(8)).apply(any(), eq("token1"))
        val expectedStatus = json.decodeFromString<FlagsAppliedMap>(cacheFile.readText())["token1"]
            ?.get("test-kotlin-flag-1")?.eventStatus
        assertEquals(EventStatus.CREATED, expectedStatus)
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)

        // Evaluate a flag property in order to trigger an apply
        confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "empty",
            evaluationContext
        )

        advanceUntilIdle()
        val captor = argumentCaptor<List<AppliedFlag>>()
        verify(mockClient, times(9)).apply(captor.capture(), eq("token1"))
        assertEquals(1, captor.firstValue.count())
        assertEquals("test-kotlin-flag-1", captor.firstValue.first().flag)

        assertEquals(0, Json.parseToJsonElement(cacheFile.readText()).jsonObject.size)
        assertEquals("red", evalString.value)
        assertEquals(false, evalBool.value)
        assertEquals(7, evalInteger.value)
        assertEquals(3.14, evalDouble.value)
        assertEquals("2023-03-01T14:01:46.645Z", evalDate.value)
        assertEquals(
            Value.Structure(mapOf("innerString" to Value.String("innerValue"))),
            evalObject.value
        )
        assertEquals("innerValue", evalNested.value)
        assertEquals("error", evalNull.value)

        assertEquals(Reason.TARGETING_MATCH.toString(), evalString.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalBool.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalInteger.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalDouble.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalDate.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalObject.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalNested.reason)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalNull.reason)

        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalString.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalBool.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalInteger.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalDouble.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalDate.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalObject.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalNested.variant)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalNull.variant)

        assertNull(evalString.errorMessage)
        assertNull(evalBool.errorMessage)
        assertNull(evalInteger.errorMessage)
        assertNull(evalDouble.errorMessage)
        assertNull(evalDate.errorMessage)
        assertNull(evalObject.errorMessage)
        assertNull(evalNested.errorMessage)
        assertNull(evalNull.errorMessage)

        assertNull(evalString.errorCode)
        assertNull(evalBool.errorCode)
        assertNull(evalInteger.errorCode)
        assertNull(evalDouble.errorCode)
        assertNull(evalDate.errorCode)
        assertNull(evalObject.errorCode)
        assertNull(evalNested.errorCode)
        assertNull(evalNull.errorCode)
    }

    @Test
    fun testNewContextFetchValuesAgain() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            client = mockClient,
            eventHandler = eventHandler,
            dispatcher = testDispatcher
        )

        val evaluationContext1 = ImmutableContext("foo")
        val evaluationContext2 = ImmutableContext("bar")
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        whenever(mockClient.resolve(eq(listOf()), eq(evaluationContext1))).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )

        val newExpectedValue =
            resolvedFlags.list[0].copy(value = ImmutableStructure(blueStringValues))
        whenever(mockClient.resolve(eq(listOf()), eq(evaluationContext2))).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(Flags(listOf(newExpectedValue)), "token1")
            )
        )

        confidenceFeatureProvider.initialize(evaluationContext1)
        advanceUntilIdle()

        val evalString1 = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "default",
            evaluationContext1
        )
        assertEquals("red", evalString1.value)
        confidenceFeatureProvider.onContextSet(evaluationContext1, evaluationContext2)
        advanceUntilIdle()
        val evalString2 = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "default",
            evaluationContext2
        )
        assertEquals("blue", evalString2.value)
    }

    @Test
    fun testApplyOnMultipleEvaluations() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            client = mockClient,
            eventHandler = eventHandler,
            dispatcher = testDispatcher
        )
        val cacheFile = File(mockContext.filesDir, APPLY_FILE_NAME)
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)

        val evaluationContext1 = ImmutableContext("foo")
        val evaluationContext2 = ImmutableContext("bar")

        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )
        confidenceFeatureProvider.initialize(evaluationContext1)
        advanceUntilIdle()
        verify(mockClient, times(1)).resolve(any(), eq(evaluationContext1))

        val evalString1 = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "default",
            evaluationContext1
        )
        // Second evaluation shouldn't trigger apply
        confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "default",
            evaluationContext1
        )

        advanceUntilIdle()
        verify(mockClient, times(1)).apply(any(), eq("token1"))
        assertEquals(0, Json.parseToJsonElement(cacheFile.readText()).jsonObject.size)

        val captor1 = argumentCaptor<List<AppliedFlag>>()
        verify(mockClient, times(1)).apply(captor1.capture(), eq("token1"))

        assertEquals(1, captor1.firstValue.count())
        assertEquals("test-kotlin-flag-1", captor1.firstValue.first().flag)
        assertEquals("red", evalString1.value)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalString1.reason)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalString1.variant)
        assertNull(evalString1.errorMessage)
        assertNull(evalString1.errorCode)

        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token2")
            )
        )
        confidenceFeatureProvider.onContextSet(evaluationContext1, evaluationContext2)
        advanceUntilIdle()
        verify(mockClient, times(1)).resolve(any(), eq(evaluationContext2))

        // Third evaluation with different context should trigger apply
        val evalString2 = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "default",
            evaluationContext2
        )

        advanceUntilIdle()
        verify(mockClient, times(1)).apply(any(), eq("token2"))
        assertEquals(0, Json.parseToJsonElement(cacheFile.readText()).jsonObject.size)
        val captor = argumentCaptor<List<AppliedFlag>>()
        verify(mockClient, times(1)).apply(captor.capture(), eq("token2"))

        assertEquals(1, captor.firstValue.count())
        assertEquals("test-kotlin-flag-1", captor.firstValue.first().flag)
        assertEquals("red", evalString2.value)
        assertEquals(Reason.TARGETING_MATCH.toString(), evalString2.reason)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalString2.variant)
        assertNull(evalString2.errorMessage)
        assertNull(evalString2.errorCode)
    }

    @Test
    fun testApplyFromStoredCache() = runTest {
        val cacheFile = File(mockContext.filesDir, APPLY_FILE_NAME)
        cacheFile.writeText(
            "{\"token1\":{\"test-kotlin-flag-1\":{\"time\":\"2023-06-26T11:55:33.184774Z\",\"eventStatus\":\"CREATED\"}}}"
        )

        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            client = mockClient,
            eventHandler = eventHandler,
            dispatcher = testDispatcher
        )

        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)

        val evaluationContext = ImmutableContext("foo")
        confidenceFeatureProvider.initialize(evaluationContext)
        advanceUntilIdle()

        verify(mockClient, times(1)).resolve(any(), eq(evaluationContext))

        confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "empty",
            evaluationContext
        )
        advanceUntilIdle()
        verify(mockClient, times(1)).apply(any(), eq("token1"))
    }

    @Test
    fun testApplyFromStoredCacheSendingStatus() = runTest {
        val cacheFile = File(mockContext.filesDir, APPLY_FILE_NAME)
        cacheFile.writeText(
            "{\"token1\":{\"test-kotlin-flag-1\":{\"time\":\"2023-06-26T11:55:33.184774Z\",\"eventStatus\":\"SENDING\"}}}"
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            client = mockClient,
            eventHandler = eventHandler,
            dispatcher = testDispatcher
        )

        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )

        val evaluationContext = ImmutableContext("foo")
        confidenceFeatureProvider.initialize(evaluationContext)
        advanceUntilIdle()

        verify(mockClient, times(1)).resolve(any(), eq(evaluationContext))

        confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "empty",
            evaluationContext
        )
        advanceUntilIdle()
        verify(mockClient, times(1)).apply(any(), eq("token1"))
    }

    @Test
    fun testNotSendDuplicateWhileSending() = runTest {
        val cacheFile = File(mockContext.filesDir, APPLY_FILE_NAME)
        cacheFile.writeText(
            "{\"token1\":{\"test-kotlin-flag-1\":{\"time\":\"2023-06-26T11:55:33.184774Z\",\"eventStatus\":\"CREATED\"}}}"
        )
        whenever(mockClient.apply(any(), any())).then { }
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            client = mockClient,
            eventHandler = eventHandler,
            dispatcher = testDispatcher
        )

        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )

        val evaluationContext = ImmutableContext("foo")
        confidenceFeatureProvider.initialize(evaluationContext)
        advanceUntilIdle()

        verify(mockClient, times(1)).resolve(any(), eq(evaluationContext))

        confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "empty",
            evaluationContext
        )
        advanceUntilIdle()
        confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.myboolean",
            "false",
            evaluationContext
        )
        advanceUntilIdle()
        verify(mockClient, times(1)).apply(any(), eq("token1"))
    }

    @Test
    fun testDoSendAgainWhenNetworkRequestFailed() = runTest {
        val cacheFile = File(mockContext.filesDir, APPLY_FILE_NAME)
        cacheFile.writeText(
            "{\"token1\":{\"test-kotlin-flag-1\":{\"time\":\"2023-06-26T11:55:33.184774Z\",\"eventStatus\":\"CREATED\"}}}"
        )

        whenever(mockClient.apply(any(), any())).thenReturn(Result.Failure)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            client = mockClient,
            eventHandler = eventHandler,
            dispatcher = testDispatcher
        )

        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )

        val evaluationContext = ImmutableContext("foo")
        confidenceFeatureProvider.initialize(evaluationContext)
        advanceUntilIdle()

        verify(mockClient, times(1)).resolve(any(), eq(evaluationContext))

        confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "empty",
            evaluationContext
        )
        advanceUntilIdle()
        confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.myboolean",
            "false",
            evaluationContext
        )
        advanceUntilIdle()
        verify(mockClient, times(3)).apply(any(), eq("token1"))
    }

    @Test
    fun testOnProcessBatchOnInitAndEval() = runTest {
        val cacheFile = File(mockContext.filesDir, APPLY_FILE_NAME)
        cacheFile.writeText(cacheFileData)
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            client = mockClient,
            eventHandler = eventHandler,
            dispatcher = testDispatcher
        )

        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token2")
            )
        )

        val evaluationContext = ImmutableContext("foo")

        confidenceFeatureProvider.initialize(evaluationContext)
        advanceUntilIdle()
        confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "default",
            evaluationContext
        )

        advanceUntilIdle()
        verify(mockClient, times(0)).apply(any(), eq("token1"))
        verify(mockClient, times(2)).apply(any(), eq("token2"))
        verify(mockClient, times(1)).apply(any(), eq("token3"))
        assertEquals(0, Json.parseToJsonElement(cacheFile.readText()).jsonObject.size)
    }

    @Test
    fun testOnProcessBatchOnInit() = runTest {
        val cacheFile = File(mockContext.filesDir, APPLY_FILE_NAME)
        cacheFile.writeText(cacheFileData)
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            client = mockClient,
            eventHandler = eventHandler,
            dispatcher = testDispatcher
        )

        advanceUntilIdle()
        verify(mockClient, times(0)).apply(any(), eq("token1"))
        verify(mockClient, times(1)).apply(any(), eq("token2"))
        verify(mockClient, times(1)).apply(any(), eq("token3"))
        assertEquals(0, Json.parseToJsonElement(cacheFile.readText()).jsonObject.size)
    }

    @Test
    fun testMatchingRootObject() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            eventHandler = eventHandler,
            dispatcher = testDispatcher,
            client = mockClient
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )
        confidenceFeatureProvider.initialize(ImmutableContext("foo"))
        advanceUntilIdle()
        val evalRootObject = confidenceFeatureProvider
            .getObjectEvaluation(
                "test-kotlin-flag-1",
                Value.Structure(mapOf()),
                ImmutableContext("foo")
            )

        assertEquals(resolvedValueAsMap, evalRootObject.value.asStructure())
        assertEquals(Reason.TARGETING_MATCH.toString(), evalRootObject.reason)
        assertEquals("flags/test-kotlin-flag-1/variants/variant-1", evalRootObject.variant)
        assertNull(evalRootObject.errorMessage)
        assertNull(evalRootObject.errorCode)
    }

    @Test
    fun testError() = runTest {
        val cache = InMemoryCache()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = cache,
            eventHandler = eventHandler,
            dispatcher = testDispatcher,
            client = mockClient
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )

        // Simulate a case where the context in the cache is not synced with the evaluation's context
        val cacheData = toCacheData(resolvedFlags.list, "token2", ImmutableContext("user1"))
        cache.refresh(cacheData)
        val evalString = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "default",
            ImmutableContext("user2")
        )
        val evalBool = confidenceFeatureProvider.getBooleanEvaluation(
            "test-kotlin-flag-1.myboolean",
            true,
            ImmutableContext("user2")
        )
        val evalInteger = confidenceFeatureProvider.getIntegerEvaluation(
            "test-kotlin-flag-1.myinteger",
            1,
            ImmutableContext("user2")
        )
        val evalDouble = confidenceFeatureProvider.getDoubleEvaluation(
            "test-kotlin-flag-1.mydouble",
            7.28,
            ImmutableContext("user2")
        )
        val evalDate = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mydate",
            "default1",
            ImmutableContext("user2")
        )
        val evalObject = confidenceFeatureProvider.getObjectEvaluation(
            "test-kotlin-flag-1.mystruct",
            Value.Structure(mapOf()),
            ImmutableContext("user2")
        )
        val evalNested = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystruct.innerString",
            "default2",
            ImmutableContext("user2")
        )
        val evalNull = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mynull",
            "default3",
            ImmutableContext("user2")
        )

        assertEquals("default", evalString.value)
        assertEquals(true, evalBool.value)
        assertEquals(1, evalInteger.value)
        assertEquals(7.28, evalDouble.value)
        assertEquals("default1", evalDate.value)
        assertEquals(Value.Structure(mapOf()), evalObject.value)
        assertEquals("default2", evalNested.value)
        assertEquals("default3", evalNull.value)

        assertEquals(Reason.ERROR.toString(), evalString.reason)
        assertEquals(Reason.ERROR.toString(), evalBool.reason)
        assertEquals(Reason.ERROR.toString(), evalInteger.reason)
        assertEquals(Reason.ERROR.toString(), evalDouble.reason)
        assertEquals(Reason.ERROR.toString(), evalDate.reason)
        assertEquals(Reason.ERROR.toString(), evalObject.reason)
        assertEquals(Reason.ERROR.toString(), evalNested.reason)
        assertEquals(Reason.ERROR.toString(), evalNull.reason)

        assertNull(evalString.variant)
        assertNull(evalBool.variant)
        assertNull(evalInteger.variant)
        assertNull(evalDouble.variant)
        assertNull(evalDate.variant)
        assertNull(evalObject.variant)
        assertNull(evalNested.variant)
        assertNull(evalNull.variant)

        assertNull(evalString.errorMessage)
        assertNull(evalBool.errorMessage)
        assertNull(evalInteger.errorMessage)
        assertNull(evalDouble.errorMessage)
        assertNull(evalDate.errorMessage)
        assertNull(evalObject.errorMessage)
        assertNull(evalNested.errorMessage)
        assertNull(evalNull.errorMessage)

        assertEquals(ErrorCode.PROVIDER_NOT_READY, evalString.errorCode)
        assertEquals(ErrorCode.PROVIDER_NOT_READY, evalBool.errorCode)
        assertEquals(ErrorCode.PROVIDER_NOT_READY, evalInteger.errorCode)
        assertEquals(ErrorCode.PROVIDER_NOT_READY, evalDouble.errorCode)
        assertEquals(ErrorCode.PROVIDER_NOT_READY, evalDate.errorCode)
        assertEquals(ErrorCode.PROVIDER_NOT_READY, evalObject.errorCode)
        assertEquals(ErrorCode.PROVIDER_NOT_READY, evalNested.errorCode)
        assertEquals(ErrorCode.PROVIDER_NOT_READY, evalNull.errorCode)
    }

    @Test
    fun testInvalidTargetingKey() = runTest {
        val cache = InMemoryCache()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = cache,
            eventHandler = eventHandler,
            dispatcher = testDispatcher,
            client = mockClient
        )

        val resolvedFlagInvalidKey = Flags(
            listOf(
                ResolvedFlag(
                    "test-kotlin-flag-1",
                    "",
                    ImmutableStructure(mapOf()),
                    ResolveReason.RESOLVE_REASON_TARGETING_KEY_ERROR
                )
            )
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlagInvalidKey, "token1")
            )
        )

        val cacheData = toCacheData(
            resolvedFlagInvalidKey.list,
            "token",
            ImmutableContext("user1")
        )
        cache.refresh(cacheData)
        val evalString = confidenceFeatureProvider.getStringEvaluation(
            "test-kotlin-flag-1.mystring",
            "default",
            ImmutableContext("user1")
        )
        assertEquals("default", evalString.value)
        assertEquals(Reason.ERROR.toString(), evalString.reason)
        assertEquals(evalString.errorMessage, "Invalid targeting key")
        assertEquals(evalString.errorCode, ErrorCode.INVALID_CONTEXT)
    }

    @Test
    fun testNonMatching() = runTest {
        val cache = InMemoryCache()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = cache,
            eventHandler = eventHandler,
            dispatcher = testDispatcher,
            client = mockClient
        )

        val resolvedNonMatchingFlags = Flags(
            listOf(
                ResolvedFlag(
                    flag = "test-kotlin-flag-1",
                    variant = "",
                    ImmutableStructure(mutableMapOf()),
                    ResolveReason.RESOLVE_REASON_NO_TREATMENT_MATCH
                )
            )
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedNonMatchingFlags, "token1")
            )
        )

        confidenceFeatureProvider.initialize(ImmutableContext("user1"))
        advanceUntilIdle()

        val evalString = confidenceFeatureProvider
            .getStringEvaluation(
                "test-kotlin-flag-1.mystring",
                "default",
                ImmutableContext("user1")
            )

        assertNull(evalString.errorMessage)
        assertNull(evalString.errorCode)
        assertNull(evalString.variant)
        assertEquals("default", evalString.value)
        assertEquals(Reason.DEFAULT.toString(), evalString.reason)
    }

    @Test
    fun testFlagNotFound() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val cache = InMemoryCache()
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = cache,
            eventHandler = eventHandler,
            dispatcher = testDispatcher,
            client = mockClient
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )
        // Simulate a case where the context in the cache is not synced with the evaluation's context
        // This shouldn't have an effect in this test, given that not found values are priority over stale values
        val cacheData = toCacheData(
            resolvedFlags.list,
            "token2",
            ImmutableContext("user1")
        )
        cache.refresh(cacheData)
        val ex = assertThrows(FlagNotFoundError::class.java) {
            confidenceFeatureProvider.getStringEvaluation(
                "test-kotlin-flag-2.mystring",
                "default",
                ImmutableContext("user2")
            )
        }
        assertEquals("Could not find flag named: test-kotlin-flag-2", ex.message)
    }

    @Test
    fun testErrorInNetwork() = runTest {
        val cache = InMemoryCache()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = cache,
            eventHandler = eventHandler,
            dispatcher = testDispatcher,
            client = mockClient
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        whenever(mockClient.resolve(eq(listOf()), any())).thenThrow(Error())
        confidenceFeatureProvider.initialize(ImmutableContext("user1"))
        advanceUntilIdle()
        val ex = assertThrows(FlagNotFoundError::class.java) {
            confidenceFeatureProvider.getStringEvaluation(
                "test-kotlin-flag-2.mystring",
                "default",
                ImmutableContext("user1")
            )
        }
        assertEquals("Could not find flag named: test-kotlin-flag-2", ex.message)
    }

    @Test
    fun whenResolveIsNotModifiedDoNotUpdateCache() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val cache = mock<InMemoryCache>()
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = cache,
            eventHandler = eventHandler,
            dispatcher = testDispatcher,
            client = mockClient
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(ResolveResponse.NotModified)
        confidenceFeatureProvider.initialize(ImmutableContext("user1"))
        advanceUntilIdle()
        verify(cache, never()).refresh(any())
    }

    @Test
    fun testValueNotFound() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            eventHandler = eventHandler,
            dispatcher = testDispatcher,
            client = mockClient
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )
        confidenceFeatureProvider.initialize(ImmutableContext("user2"))
        advanceUntilIdle()
        val ex = assertThrows(ParseError::class.java) {
            confidenceFeatureProvider.getStringEvaluation(
                "test-kotlin-flag-1.wrongid",
                "default",
                ImmutableContext("user2")
            )
        }
        assertEquals("Unable to parse flag value: wrongid", ex.message)
    }

    @Test
    fun testValueNotFoundLongPath() = runTest {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val eventHandler = EventHandler(testDispatcher)
        val confidenceFeatureProvider = ConfidenceFeatureProvider.create(
            context = mockContext,
            clientSecret = "",
            cache = InMemoryCache(),
            eventHandler = eventHandler,
            dispatcher = testDispatcher,
            client = mockClient
        )
        whenever(mockClient.apply(any(), any())).thenReturn(Result.Success)
        whenever(mockClient.resolve(eq(listOf()), any())).thenReturn(
            ResolveResponse.Resolved(
                ResolveFlags(resolvedFlags, "token1")
            )
        )
        confidenceFeatureProvider.initialize(ImmutableContext("user2"))
        advanceUntilIdle()
        val ex = assertThrows(ParseError::class.java) {
            confidenceFeatureProvider.getStringEvaluation(
                "test-kotlin-flag-1.mystring.extrapath",
                "default",
                ImmutableContext("user2")
            )
        }
        assertEquals("Unable to parse flag value: mystring/extrapath", ex.message)
    }
}