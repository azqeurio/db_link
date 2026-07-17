package dev.dblink.core.rawai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RawAiConditionTest {
    @Test fun mapsRepresentativeIsoValues() {
        assertEquals(0.0f, RawAiCondition.fromIso(0f), 0f)
        assertEquals(0.03125f, RawAiCondition.fromIso(200f), 0f)
        assertEquals(1.0f, RawAiCondition.fromIso(6400f), 0f)
        assertEquals(10.23984375f, RawAiCondition.fromIso(100_000f), 0f)
    }

    @Test fun rejectsInvalidIso() {
        assertThrows(IllegalArgumentException::class.java) { RawAiCondition.fromIso(-1f) }
        assertThrows(IllegalArgumentException::class.java) { RawAiCondition.fromIso(Float.NaN) }
    }
}
