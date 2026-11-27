package tv.own.owntv.core.metadata

import tv.own.owntv.core.network.HttpClient
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/**
 * Why the last metadata request failed, for the Settings connection test.
 *
 * Callers of [MetadataProvider] only see null for "transport failure", which is right for the
 * caching logic but left the test showing one message for everything: a rejected key, a missing
 * DNS entry and a wrong TV clock all read "couldn't reach the metadata server".
 */
sealed interface MetadataFetchFailure {
    /** HTTP 401 while calling TMDB directly with the user's own key. */
    data object KeyRejected : MetadataFetchFailure
    data object RateLimited : MetadataFetchFailure
    data class Http(val code: Int) : MetadataFetchFailure
    data class HostNotFound(val host: String) : MetadataFetchFailure
    data object TimedOut : MetadataFetchFailure
    /** TLS handshake or certificate failure; on a TV most often a wrong date and time. */
    data object SecureConnection : MetadataFetchFailure
    data object ConnectionFailed : MetadataFetchFailure
    /** The shared default Worker's per-install allowance is used up; nothing was sent. */
    data object AllowanceUsed : MetadataFetchFailure
    data class Other(val message: String?) : MetadataFetchFailure
}

/** Classifies [t], walking its causes, since HTTP stacks often wrap the interesting exception. */
internal fun classifyMetadataFailure(t: Throwable, host: String, usingOwnKey: Boolean): MetadataFetchFailure {
    var cur: Throwable? = t
    var depth = 0
    while (cur != null && depth < 8) {
        when (cur) {
            is HttpClient.HttpStatusException -> return when {
                cur.code == 401 && usingOwnKey -> MetadataFetchFailure.KeyRejected
                cur.code == 429 -> MetadataFetchFailure.RateLimited
                else -> MetadataFetchFailure.Http(cur.code)
            }
            is UnknownHostException -> return MetadataFetchFailure.HostNotFound(host)
            is SocketTimeoutException -> return MetadataFetchFailure.TimedOut
            is SSLException, is CertificateException -> return MetadataFetchFailure.SecureConnection
            is ConnectException -> return MetadataFetchFailure.ConnectionFailed
        }
        cur = cur.cause
        depth++
    }
    return if (t is IOException) MetadataFetchFailure.ConnectionFailed else MetadataFetchFailure.Other(t.message)
}

/**
 * The user's TMDB key with everything removed that can never be part of one: whitespace, including
 * non-breaking spaces, control characters and invisible format characters such as zero-width spaces
 * and byte-order marks. Copying a key from a web page or a notes app can pick these up, and TMDB then
 * rejects the key while it looks perfect on screen. Letters and digits are left alone, so a pasted
 * Read Access Token is not mangled into something that merely looks like a key.
 */
fun cleanTmdbApiKey(raw: String): String = raw.filter { c ->
    !c.isWhitespace() && !Character.isSpaceChar(c) && !Character.isISOControl(c) &&
        Character.getType(c) != Character.FORMAT.toInt()
}
