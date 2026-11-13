package kr.phishguard.app.family

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepAlivePolicyTest {
    @Test
    fun growsToCapAndShrinksToFloor() {
        assertEquals(90_000L, KeepAlivePolicy.grow(45_000L))
        assertEquals(180_000L, KeepAlivePolicy.grow(90_000L))
        assertEquals(240_000L, KeepAlivePolicy.grow(180_000L)) // 최대 4분
        assertEquals(240_000L, KeepAlivePolicy.grow(240_000L))
        assertEquals(120_000L, KeepAlivePolicy.shrink(240_000L))
        assertEquals(45_000L, KeepAlivePolicy.shrink(60_000L)) // 최소 45초
    }
}
