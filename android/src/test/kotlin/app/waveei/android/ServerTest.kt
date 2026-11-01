package app.waveei.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which failed calls are worth paying for again. */
class ServerTest {
    @Test
    fun aRefusedRequestIsNotTriedAgainButABusyServerIs() {
        assertTrue(Server.Failure("bad request", 400).final)
        assertTrue(Server.Failure("denied", 403).final)
        assertTrue(Server.Failure("cut off", final = true).final)
        assertFalse(Server.Failure("quota", 429).final)
        assertFalse(Server.Failure("timeout", 408).final)
        assertFalse(Server.Failure("busy", 503).final)
        assertFalse(Server.Failure("not a transcript").final)
    }
}
