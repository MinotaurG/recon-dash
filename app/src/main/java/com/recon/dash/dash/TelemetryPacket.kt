package com.recon.dash.dash

data class TelemetryPacket(
    val timestampMs: Long,
    val type: Int,
    val sub: Int,
    val raw: ByteArray,
    val decrypted: ByteArray?,
) {
    val typeHex: String get() = "%02X".format(type)
    val subHex: String get() = "%02X".format(sub)
    val decHex: String get() = decrypted?.joinToString(" ") { "%02X".format(it) } ?: "decrypt failed"
    val rawHex: String get() = raw.joinToString(" ") { "%02X".format(it) }
    val label: String get() = "$typeHex:$subHex"
}
