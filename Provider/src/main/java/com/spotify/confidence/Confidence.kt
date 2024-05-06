package com.spotify.confidence

import android.content.Context
import com.spotify.confidence.apply.FlagApplierWithRetries
import com.spotify.confidence.cache.DiskStorage
import com.spotify.confidence.cache.FileDiskStorage
import com.spotify.confidence.client.ConfidenceRegion
import com.spotify.confidence.client.FlagApplierClient
import com.spotify.confidence.client.FlagApplierClientImpl
import com.spotify.confidence.client.SdkMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class Confidence internal constructor(
    private val clientSecret: String,
    private val dispatcher: CoroutineDispatcher,
    private val eventSenderEngine: EventSenderEngine,
    private val diskStorage: DiskStorage,
    private val flagResolver: FlagResolver,
    private val flagApplierClient: FlagApplierClient,
    private val parent: ConfidenceContextProvider? = null,
    private val region: ConfidenceRegion = ConfidenceRegion.GLOBAL
) : Contextual, EventSender {
    private val removedKeys = mutableListOf<String>()
    private val contextMap = MutableStateFlow(mapOf<String, ConfidenceValue>())

    // only return changes not the initial value
    // only return distinct value
    internal val contextChanges: Flow<Map<String, ConfidenceValue>> = contextMap
        .drop(1)
        .distinctUntilChanged()
    private val coroutineScope = CoroutineScope(dispatcher)
    private val eventProducers: MutableList<EventProducer> = mutableListOf()

    private val flagApplier = FlagApplierWithRetries(
        client = flagApplierClient,
        dispatcher = dispatcher,
        diskStorage = diskStorage
    )

    internal suspend fun resolve(flags: List<String>): Result<FlagResolution> {
        return flagResolver.resolve(flags, getContext())
    }

    internal fun apply(flagName: String, resolveToken: String) {
        flagApplier.apply(flagName, resolveToken)
    }

    @Synchronized
    override fun putContext(key: String, value: ConfidenceValue) {
        val map = contextMap.value.toMutableMap()
        map[key] = value
        contextMap.value = map
    }

    @Synchronized
    override fun putContext(context: Map<String, ConfidenceValue>) {
        val map = contextMap.value.toMutableMap()
        map += context
        contextMap.value = map
    }

    @Synchronized
    internal fun putContext(context: Map<String, ConfidenceValue>, removedKeys: List<String>) {
        val map = contextMap.value.toMutableMap()
        map += context
        for (key in removedKeys) {
            map.remove(key)
        }
        this.removedKeys.addAll(removedKeys)
        contextMap.value = map
    }

    @Synchronized
    override fun removeContext(key: String) {
        val map = contextMap.value.toMutableMap()
        map.remove(key)
        removedKeys.add(key)
        contextMap.value = map
    }

    override fun getContext(): Map<String, ConfidenceValue> =
        this.parent?.let {
            it.getContext().filterKeys { key -> !removedKeys.contains(key) } + contextMap.value
        } ?: contextMap.value

    override fun withContext(context: Map<String, ConfidenceValue>): Confidence = Confidence(
        clientSecret,
        dispatcher,
        eventSenderEngine,
        diskStorage,
        flagResolver,
        flagApplierClient,
        this,
        region
    ).also {
        it.putContext(context)
    }

    override fun track(
        eventName: String,
        message: ConfidenceFieldsType
    ) {
        eventSenderEngine.emit(eventName, message, getContext())
    }

    override fun track(eventProducer: EventProducer) {
        coroutineScope.launch {
            eventProducer
                .events()
                .collect { event ->
                    eventSenderEngine.emit(
                        event.name,
                        event.message,
                        getContext()
                    )
                }
        }

        coroutineScope.launch {
            eventProducer.contextChanges()
                .collect(this@Confidence::putContext)
        }
        eventProducers.add(eventProducer)
    }

    override fun stop() {
        for (producer in eventProducers) {
            producer.stop()
        }
        if (parent == null) {
            eventSenderEngine.stop()
        } else {
            // no-op for child confidence
            coroutineScope.cancel()
        }
    }
}

internal const val VISITOR_ID_CONTEXT_KEY = "visitorId"

object ConfidenceFactory {
    fun create(
        context: Context,
        clientSecret: String,
        region: ConfidenceRegion = ConfidenceRegion.GLOBAL,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Confidence {
        val engine = EventSenderEngineImpl.instance(
            context,
            clientSecret,
            flushPolicies = listOf(minBatchSizeFlushPolicy),
            sdkMetadata = SdkMetadata(SDK_ID, BuildConfig.SDK_VERSION),
            dispatcher = dispatcher
        )
        val flagApplierClient = FlagApplierClientImpl(
            clientSecret,
            SdkMetadata(SDK_ID, BuildConfig.SDK_VERSION),
            region,
            dispatcher
        )

        val flagResolver = RemoteFlagResolver(
            clientSecret = clientSecret,
            region = region,
            httpClient = OkHttpClient(),
            dispatcher = dispatcher,
            sdkMetadata = SdkMetadata(SDK_ID, BuildConfig.SDK_VERSION)
        )
        return Confidence(
            clientSecret,
            dispatcher,
            engine,
            region = region,
            flagResolver = flagResolver,
            diskStorage = FileDiskStorage.create(context),
            flagApplierClient = flagApplierClient
        ).apply {
            putContext(VISITOR_ID_CONTEXT_KEY, ConfidenceValue.String(VisitorUtil.getId(context)))
        }
    }
}