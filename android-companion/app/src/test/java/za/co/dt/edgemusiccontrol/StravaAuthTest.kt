package za.co.dt.edgemusiccontrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two things here are worth more than they look. Misreading a token response strands the connection
 * — Strava invalidates the old refresh token the moment it mints a new one — and getting the expiry
 * arithmetic wrong means an upload starting on a token that dies mid-request.
 */
class StravaAuthTest {

    private val exchange = """
        {"token_type":"Bearer","expires_at":1787203600,"expires_in":21600,
         "refresh_token":"r2","access_token":"a2",
         "athlete":{"id":12345,"firstname":"Daniël","lastname":"du Toit"}}
    """.trimIndent()

    @Test
    fun readsTheFirstExchangeIncludingTheAthlete() {
        val grant = StravaAuth.parseGrant(exchange)

        assertNotNull(grant)
        assertEquals("a2", grant!!.tokens.accessToken)
        assertEquals("r2", grant.tokens.refreshToken)
        assertEquals(1787203600L, grant.tokens.expiresAt)
        assertEquals("Daniël du Toit", grant.athlete)
    }

    /** A refresh answers with tokens and no athlete; that is a success, not a half-read reply. */
    @Test
    fun readsARefreshWithoutAnAthlete() {
        val body = """
            {"token_type":"Bearer","access_token":"a3","expires_at":1787225200,
             "expires_in":21600,"refresh_token":"r3"}
        """.trimIndent()

        val grant = StravaAuth.parseGrant(body)

        assertNotNull(grant)
        assertEquals("a3", grant!!.tokens.accessToken)
        assertEquals("r3", grant.tokens.refreshToken)
        assertNull(grant.athlete)
    }

    @Test
    fun rejectsAReplyWithoutBothTokens() {
        assertNull(StravaAuth.parseGrant("""{"access_token":"a4","expires_at":1787203600}"""))
        assertNull(StravaAuth.parseGrant("""{"refresh_token":"r4"}"""))
        assertNull(StravaAuth.parseGrant("""{"message":"Bad Request","errors":[]}"""))
        assertNull(StravaAuth.parseGrant("<html>502 Bad Gateway</html>"))
    }

    @Test
    fun aTokenWithHoursLeftIsUsedAsIs() {
        assertFalse(StravaAuth.needsRefresh(1787203600L, 1787182000L))
    }

    @Test
    fun aTokenAboutToLapseIsRefreshedFirst() {
        // Four minutes left: inside the five-minute margin, so it is replaced before it is used.
        assertTrue(StravaAuth.needsRefresh(1787203600L, 1787203360L))
    }

    @Test
    fun anExpiredOrUnknownExpiryAlwaysRefreshes() {
        assertTrue(StravaAuth.needsRefresh(1787203600L, 1787207200L))
        assertTrue(StravaAuth.needsRefresh(0L, 1787207200L))
    }

    @Test
    fun theAuthorisationUrlCarriesTheCallbackAndScope() {
        val url = StravaAuth.authorizeUrl("54321")

        assertTrue(url.startsWith("https://www.strava.com/oauth/mobile/authorize?"))
        assertTrue(url.contains("client_id=54321"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2Flocalhost%2Fexchange_token"))
        assertTrue(url.contains("response_type=code"))
        // activity:read_all, not the plain read scope: without it the activity endpoint answers
        // 404 and the trainer flag can never be cleared.
        assertTrue(url.contains("scope=activity%3Aread_all%2Cactivity%3Awrite"))
    }
}
