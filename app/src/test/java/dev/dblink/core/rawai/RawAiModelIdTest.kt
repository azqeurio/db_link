package dev.dblink.core.rawai

import org.junit.Assert.assertEquals
import org.junit.Test

class RawAiModelIdTest {
    @Test fun phase5RegistryContainsExactlyTwoStableIds() {
        assertEquals(
            listOf("RAWFORGE_STANDARD_FP32", "RAWFORGE_SUPERLIGHT_FP32"),
            RawAiModelId.entries.map(RawAiModelId::stableId),
        )
    }
}
