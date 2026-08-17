package com.shinegirls.apkadremovereditor.utils

object BinaryUtils {

    private val HEX_CHARS = "0123456789ABCDEF".toCharArray()

    fun bytesToHex(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): String {
        require(offset >= 0 && offset <= bytes.size) { "offset out of range" }
        val end = minOf(offset + length, bytes.size)
        val sb = StringBuilder((end - offset) * 3)
        for (i in offset until end) {
            val b = bytes[i].toInt() and 0xFF
            sb.append(HEX_CHARS[b ushr 4])
            sb.append(HEX_CHARS[b and 0x0F])
            sb.append(' ')
        }
        return sb.toString().trim()
    }

    fun hexToBytes(hex: String): ByteArray {
        val cleaned = hex.replace(" ", "").replace("\n", "")
        require(cleaned.length % 2 == 0) { "hex string length must be even" }
        val len = cleaned.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            val hi = Character.digit(cleaned[i], 16)
            val lo = Character.digit(cleaned[i + 1], 16)
            require(hi >= 0 && lo >= 0) { "invalid hex character at index $i" }
            data[i / 2] = ((hi shl 4) or lo).toByte()
        }
        return data
    }

    fun readLe32(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 3 < bytes.size) { "offset out of range" }
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun writeLe32(bytes: ByteArray, offset: Int, value: Int) {
        require(offset >= 0 && offset + 3 < bytes.size) { "offset out of range" }
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }
}