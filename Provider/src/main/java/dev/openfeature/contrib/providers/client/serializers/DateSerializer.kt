package dev.openfeature.contrib.providers.client.serializers

import android.annotation.SuppressLint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.lang.IllegalArgumentException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone

internal object DateSerializer : KSerializer<Date> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Date", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Date) =
        encoder.encodeString(simpleDateFormatter.format(value))

    override fun deserialize(decoder: Decoder): Date = simpleDateFormatter.parse(decoder.decodeString())
        ?: throw IllegalArgumentException(
            "Unable to parse ${decoder.decodeString()} as Date with pattern ${simpleDateFormatter.toPattern()}"
        )
}

@SuppressLint("SimpleDateFormat")
private val simpleDateFormatter =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").apply { timeZone = TimeZone.getTimeZone("UTC") }