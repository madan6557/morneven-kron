package com.morneven.kron.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictCenterTest {
    @Test
    fun previewAutoMergesIndependentChangesAndBlocksHashMismatch() {
        val shared = event("shared", "same")
        val deviceOnly = event("device", "device")
        val driveOnly = event("drive", "drive")
        val corruptDevice = event("corrupt", "hash-a")
        val corruptDrive = event("corrupt", "hash-b")
        val basePortfolio = mutable("portfolio", "p1", "base")
        val devicePortfolio = mutable("portfolio", "p1", "base")
        val drivePortfolio = mutable("portfolio", "p1", "drive-change", revision = 2)
        val preview = ConflictPreviewBuilder.build(
            device = ConflictDataset("local-head", listOf(shared, deviceOnly, corruptDevice), listOf(devicePortfolio)),
            drive = ConflictDataset("drive-head", listOf(shared, driveOnly, corruptDrive), listOf(drivePortfolio)),
            base = ConflictDataset("base", emptyList(), listOf(basePortfolio)),
        )

        assertEquals(1, preview.deviceOnlyCount)
        assertEquals(1, preview.driveOnlyCount)
        assertEquals(1, preview.identicalCount)
        assertEquals(1, preview.differentCount)
        assertEquals(1, preview.integrityProblemCount)
        assertEquals(
            ConflictChoice.DRIVE,
            preview.items.single { it.key == "portfolio:p1" }.automaticChoice,
        )
        assertTrue(runCatching { ConflictPreviewBuilder.mergePlan(preview, emptyMap()) }.isFailure)
    }

    private fun event(id: String, hash: String) = ConflictEventRecord(
        eventId = id,
        type = "EXPENSE",
        title = id,
        amount = 1,
        effectiveEpochDay = 1,
        accountName = "Akun",
        reversed = false,
        receiptCount = 0,
        actor = "actor",
        deviceId = "device",
        changedAtEpochMillis = 1,
        canonicalHash = hash,
        signatureHash = hash,
    )

    private fun mutable(type: String, id: String, hash: String, revision: Long = 1) = ConflictMutableRecord(
        entityType = type,
        syncId = id,
        label = id,
        revision = revision,
        canonicalHash = hash,
    )
}
