package com.bhanu.attendance.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hashes and verifies PINs with PBKDF2-HMAC-SHA256.
 *
 * Why the JDK's own PBKDF2 rather than bcrypt or Argon2: it is in `javax.crypto`, so it adds
 * no dependency and no native library to an APK that already ships MediaPipe's `.so` files.
 * A 4-to-12 digit PIN is a weak secret by construction, so a memory-hard KDF would buy little
 * over a well-iterated PBKDF2 — and the honest control here is that the database is on-device
 * and the app is offline. The iterations count is the thing to raise if this were ever
 * adapted to real passwords.
 *
 * A per-staff random salt means two people who pick the same PIN do not share a hash, so the
 * table cannot be used to spot identical PINs.
 */
interface PinHasher {
    fun hash(pin: String): PinHash
    fun verify(pin: String, hash: PinHash): Boolean
}

data class PinHash(
    val hash: ByteArray,
    val salt: ByteArray,
    val iterations: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PinHash) return false
        return hash.contentEquals(other.hash) && salt.contentEquals(other.salt) && iterations == other.iterations
    }

    override fun hashCode(): Int =
        (31 * hash.contentHashCode() + salt.contentHashCode()) * 31 + iterations
}

class Pbkdf2PinHasher(
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val keyLengthBits: Int = 256,
) : PinHasher {

    private val secureRandom = SecureRandom()

    override fun hash(pin: String): PinHash {
        val salt = ByteArray(SALT_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
        val derived = derive(pin, salt, iterations)
        return PinHash(derived, salt, iterations)
    }

    override fun verify(pin: String, hash: PinHash): Boolean {
        val derived = runCatching { derive(pin, hash.salt, hash.iterations) }.getOrNull() ?: return false
        // Constant-time comparison, so verification does not leak how many leading bytes were
        // correct through timing.
        return MessageDigest.isEqual(derived, hash.hash)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, keyLengthBits)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    companion object {
        const val ALGORITHM = "PBKDF2WithHmacSHA256"
        const val DEFAULT_ITERATIONS = 120_000
        const val SALT_LENGTH_BYTES = 16

        /** Recognised so hashes written by an older iteration count still verify. */
        val SUPPORTED_ALGORITHMS = setOf(ALGORITHM)
    }
}
