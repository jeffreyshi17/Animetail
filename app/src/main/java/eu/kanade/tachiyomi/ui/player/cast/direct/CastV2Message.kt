package eu.kanade.tachiyomi.ui.player.cast.direct

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream

/**
 * The CastV2 wire message (`cast_channel.proto` `CastMessage`), as sent over the TLS socket on port 8009.
 *
 * `protocol_version` and `payload_type` are proto2 `required` enums whose only values we use are 0
 * (`CASTV2_1_0` and `STRING`), so they are modelled as ints and always encoded.
 */
@Serializable
data class CastV2Message(
    @ProtoNumber(1) val protocolVersion: Int = PROTOCOL_VERSION_CASTV2_1_0,
    @ProtoNumber(2) val sourceId: String,
    @ProtoNumber(3) val destinationId: String,
    @ProtoNumber(4) val namespace: String,
    @ProtoNumber(5) val payloadType: Int = PAYLOAD_TYPE_STRING,
    @ProtoNumber(6) val payloadUtf8: String? = null,
) {
    companion object {
        const val PROTOCOL_VERSION_CASTV2_1_0 = 0
        const val PAYLOAD_TYPE_STRING = 0

        /** Chromecasts drop the connection on frames larger than this. */
        const val MAX_FRAME_BYTES = 64 * 1024

        private val proto = ProtoBuf { encodeDefaults = true }

        fun encode(message: CastV2Message): ByteArray = proto.encodeToByteArray(message)

        fun decode(bytes: ByteArray): CastV2Message = proto.decodeFromByteArray(bytes)

        /** Writes one frame: a 4-byte big-endian length followed by the encoded message. */
        fun writeFrame(output: OutputStream, message: CastV2Message) {
            val body = encode(message)
            require(body.size <= MAX_FRAME_BYTES) { "Cast message too large: ${body.size} bytes" }
            val frame = ByteArray(4 + body.size)
            frame[0] = (body.size ushr 24).toByte()
            frame[1] = (body.size ushr 16).toByte()
            frame[2] = (body.size ushr 8).toByte()
            frame[3] = body.size.toByte()
            body.copyInto(frame, 4)
            output.write(frame)
            output.flush()
        }

        /** Reads one frame, blocking. Throws [EOFException] when the receiver closes the socket. */
        fun readFrame(input: DataInputStream): CastV2Message {
            val length = input.readInt()
            if (length < 0 || length > MAX_FRAME_BYTES) throw IOException("Invalid cast frame length $length")
            val body = ByteArray(length)
            input.readFully(body)
            return decode(body)
        }
    }
}
