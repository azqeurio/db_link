package dev.dblink.core.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OiShareImageListContinuationPolicyTest {
    @Test
    fun `continues when a camera-sized page adds new entries`() {
        assertTrue(
            OiShareImageListContinuationPolicy.shouldRequestAnotherPage(
                receivedCount = 250,
                newEntryCount = 250,
            ),
        )
    }

    @Test
    fun `stops when a small final page is received`() {
        assertFalse(
            OiShareImageListContinuationPolicy.shouldRequestAnotherPage(
                receivedCount = 38,
                newEntryCount = 38,
            ),
        )
    }

    @Test
    fun `does not continue after a full-list response adds many entries at once`() {
        assertFalse(
            OiShareImageListContinuationPolicy.shouldRequestAnotherPage(
                receivedCount = 2000,
                newEntryCount = 2000,
            ),
        )
    }

    @Test
    fun `retries one duplicate camera-sized page because some cameras need settle time`() {
        assertTrue(
            OiShareImageListContinuationPolicy.shouldRetryDuplicatePage(
                receivedCount = 250,
                newEntryCount = 0,
                duplicateRetriesUsed = 0,
            ),
        )
        assertFalse(
            OiShareImageListContinuationPolicy.shouldRetryDuplicatePage(
                receivedCount = 250,
                newEntryCount = 0,
                duplicateRetriesUsed = 1,
            ),
        )
    }

    @Test
    fun `does not duplicate-retry a full-list response`() {
        assertFalse(
            OiShareImageListContinuationPolicy.shouldRetryDuplicatePage(
                receivedCount = 2000,
                newEntryCount = 0,
                duplicateRetriesUsed = 0,
            ),
        )
    }

    @Test
    fun `readiness retries transient image-list failures before final attempt`() {
        assertTrue(
            OiShareImageListReadinessPolicy.shouldRetryAfterFailure(
                error = IllegalStateException("Camera request failed with HTTP 404."),
                attemptIndex = 0,
            ),
        )
        assertTrue(
            OiShareImageListReadinessPolicy.shouldRetryAfterFailure(
                error = IllegalStateException("read timed out"),
                attemptIndex = 1,
            ),
        )
    }

    @Test
    fun `readiness stops on non-transient or final image-list failure`() {
        assertFalse(
            OiShareImageListReadinessPolicy.shouldRetryAfterFailure(
                error = IllegalStateException("Camera request failed with HTTP 400."),
                attemptIndex = 0,
            ),
        )
        assertFalse(
            OiShareImageListReadinessPolicy.shouldRetryAfterFailure(
                error = IllegalStateException("Camera request failed with HTTP 404."),
                attemptIndex = OiShareImageListReadinessPolicy.MAX_ATTEMPTS - 1,
            ),
        )
    }
}
