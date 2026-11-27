package tv.own.owntv.core.metadata

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.network.HttpClient
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class MetadataFetchFailureTest {

    private val key = "0123456789abcdef0123456789abcdef"

    @Test
    fun keepsAPlainKey() = assertEquals(key, cleanTmdbApiKey(key))

    @Test
    fun dropsInvisibleCharacters() =
        assertEquals(key, cleanTmdbApiKey("﻿ $key​ \n"))

    @Test
    fun leavesATokenUnmangled() = assertEquals("eyJhbGciOiJIUzI1NiJ9.abc", cleanTmdbApiKey(" eyJhbGciOiJIUzI1NiJ9.abc "))

    @Test
    fun unauthorisedWithOwnKeyIsARejectedKey() = assertEquals(
        MetadataFetchFailure.KeyRejected,
        classifyMetadataFailure(HttpClient.HttpStatusException(401, "401"), "api.themoviedb.org", usingOwnKey = true),
    )

    @Test
    fun unauthorisedOnTheWorkerIsJustAStatus() = assertEquals(
        MetadataFetchFailure.Http(401),
        classifyMetadataFailure(HttpClient.HttpStatusException(401, "401"), "relay", usingOwnKey = false),
    )

    @Test
    fun findsTheCauseInsideAWrapper() = assertEquals(
        MetadataFetchFailure.HostNotFound("api.themoviedb.org"),
        classifyMetadataFailure(IOException("wrapped", UnknownHostException("x")), "api.themoviedb.org", usingOwnKey = true),
    )

    @Test
    fun classifiesTimeoutsAndTls() {
        assertEquals(MetadataFetchFailure.TimedOut, classifyMetadataFailure(SocketTimeoutException(), "h", true))
        assertEquals(MetadataFetchFailure.SecureConnection, classifyMetadataFailure(SSLHandshakeException("x"), "h", true))
    }
}
