package dreamteam.server.persistence

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The at-rest encryption key for the SQLite payload blobs (ADR 0003).
 *
 * AES-256: exactly 32 bytes. Injected at construction — **never** hardcoded,
 * committed, logged, or derived in code. Health-signal data in the DB file is
 * unreadable without this key, which is the whole point of "encrypted at rest".
 *
 * This interface is the scoped-injection seam: deployments wire it to a secret
 * store / env var / KMS; tests wire it to a fixed key. Feature and repo code
 * depend only on the interface and never see the raw bytes.
 */
fun interface EncryptionKey {
    fun bytes(): ByteArray
}

/**
 * Constructors for [EncryptionKey]. The runtime path reads from a deployment
 * secret (env var) so no key material lives in the repo. Keys are validated to
 * be AES-256 (32 bytes) at the boundary — a wrong-length key fails loudly,
 * never silently degrades to a weaker cipher.
 */
object EncryptionKeys {
    private const val KEY_LEN = 32 // AES-256

    /**
     * Runtime provider: reads a base64-encoded 32-byte key from env var
     * [varName]. Missing / malformed / wrong-length key is a fatal startup
     * error — health data does not silently fall back to "unencrypted".
     */
    fun fromBase64Env(varName: String): EncryptionKey = EncryptionKey {
        val raw = checkNotNull(System.getenv(varName)) {
            "Missing encryption key env var $varName (ADR 0003): inject it from the deployment secret, never commit it."
        }
        val key = Base64.getDecoder().decode(raw.trim())
        require(key.size == KEY_LEN) {
            "Encryption key $varName must be $KEY_LEN bytes (AES-256); got ${key.size}."
        }
        key
    }

    /** Explicit-key provider (tests / injected secrets). The caller owns the bytes. */
    fun of(bytes: ByteArray): EncryptionKey {
        require(bytes.size == KEY_LEN) { "Encryption key must be $KEY_LEN bytes (AES-256); got ${bytes.size}." }
        return EncryptionKey { bytes }
    }
}

/**
 * AES-GCM per-row authenticated encryption for the SQLite payload blobs
 * (ADR 0003). Ciphertext layout: `[12-byte random nonce][ciphertext + GCM tag]`.
 *
 * Authenticated: any tampering with the blob (or the nonce) fails decryption.
 * A fresh random nonce per write means re-saving the same record never reuses
 * keystream. Uses [javax.crypto] only — no extra dependency.
 *
 * Internal: repo code calls [SqliteStore.encrypt] / [SqliteStore.decrypt], which
 * route UTF-8 strings through here. The cipher never touches plaintext at rest.
 */
internal object PayloadCipher {
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private val rng = SecureRandom()

    fun encrypt(plain: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also(rng::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }
        return nonce + cipher.doFinal(plain)
    }

    fun decrypt(blob: ByteArray, key: ByteArray): ByteArray {
        require(blob.size > NONCE_LEN) { "Ciphertext too short to contain a nonce." }
        val nonce = blob.copyOfRange(0, NONCE_LEN)
        val ct = blob.copyOfRange(NONCE_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        }
        return cipher.doFinal(ct)
    }
}
