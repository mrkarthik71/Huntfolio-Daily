package app.assistant.security.refs

/** RFC 4648 base32 (upper case, no padding), used to write the HMAC part of a Source_Ref. */
internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BITS_PER_CHAR = 5
    private const val BITS_PER_BYTE = 8
    private const val MASK = 0x1F

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * BITS_PER_BYTE + BITS_PER_CHAR - 1) / BITS_PER_CHAR)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl BITS_PER_BYTE) or (b.toInt() and 0xFF)
            bits += BITS_PER_BYTE
            while (bits >= BITS_PER_CHAR) {
                out.append(ALPHABET[(buffer shr (bits - BITS_PER_CHAR)) and MASK])
                bits -= BITS_PER_CHAR
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        // A last partial group is padded on the right with zero bits.
        if (bits > 0) out.append(ALPHABET[(buffer shl (BITS_PER_CHAR - bits)) and MASK])
        return out.toString()
    }
}
