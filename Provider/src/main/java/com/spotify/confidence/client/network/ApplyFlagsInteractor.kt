package com.spotify.confidence.client.network

import com.spotify.confidence.client.AppliedFlag
import com.spotify.confidence.client.await
import com.spotify.confidence.client.serializers.StructureSerializer
import com.spotify.confidence.client.serializers.UUIDSerializer
import dev.openfeature.sdk.DateSerializer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.Date

internal interface ApplyFlagsInteractor : suspend (ApplyFlagsRequest) -> (Response)

internal class ApplyFlagsInteractorImpl(
    private val httpClient: OkHttpClient,
    private val baseUrl: String,
    private val dispatcher: CoroutineDispatcher
) : ApplyFlagsInteractor {

    private val headers by lazy {
        Headers.headersOf(
            "Content-Type",
            "application/json",
            "Accept",
            "application/json"
        )
    }
    override suspend fun invoke(request: ApplyFlagsRequest): Response =
        withContext(dispatcher) {
            val httpRequest = Request.Builder()
                .url("$baseUrl/v1/flags:apply")
                .headers(headers)
                .post(json.encodeToString(request).toRequestBody())
                .build()

            return@withContext httpClient.newCall(httpRequest).await()
        }
}

@Serializable
internal data class ApplyFlagsRequest(
    val flags: List<AppliedFlag>,
    @Contextual
    val sendTime: Date,
    val clientSecret: String,
    val resolveToken: String,
    val sdkId: String,
    val sdkVersion: String
)

private val json = Json {
    serializersModule = SerializersModule {
        contextual(UUIDSerializer)
        contextual(DateSerializer)
        contextual(StructureSerializer)
    }
}