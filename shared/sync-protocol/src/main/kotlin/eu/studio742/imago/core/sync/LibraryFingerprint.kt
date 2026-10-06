package eu.studio742.imago.core.sync

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * A library's identity in the backend, computed on the device.
 *
 * It never includes the address. The Immich account goes in through an HMAC keyed by the IMAGO
 * account id, so that the same Immich user cannot be correlated across different IMAGO accounts.
 */
object LibraryFingerprint {
    const val IMMICH = "immich"
    const val DEVICE = "device"

    fun immich(accountUserId: String, immichUserId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(accountUserId.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal("immich|$immichUserId".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun device(deviceId: String): String = "device|$deviceId"
}
