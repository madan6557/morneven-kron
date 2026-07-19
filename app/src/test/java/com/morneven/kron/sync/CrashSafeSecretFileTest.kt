package com.morneven.kron.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashSafeSecretFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun completedTargetWinsOverUncommittedTemporaryAndBackup() {
        val target = target()
        val temporary = File(target.parentFile, "${target.name}.new")
        val backup = File(target.parentFile, "${target.name}.bak")
        target.writeText("valid:old")
        temporary.writeText("valid:new")
        backup.writeText("valid:old")

        val recovered = store(target).recover()

        assertEquals("valid:old", recovered?.readText())
        assertFalse(temporary.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun backupRestoresLastCommittedSecretWhenReplacementWasInterrupted() {
        val target = target()
        val temporary = File(target.parentFile, "${target.name}.new")
        val backup = File(target.parentFile, "${target.name}.bak")
        temporary.writeText("valid:new")
        backup.writeText("valid:old")

        val recovered = store(target).recover()

        assertEquals("valid:old", recovered?.readText())
        assertFalse(temporary.exists())
        assertFalse(backup.exists())
    }

    @Test
    fun backupReplacesCorruptedTarget() {
        val target = target()
        val backup = File(target.parentFile, "${target.name}.bak")
        target.writeText("corrupt")
        backup.writeText("valid:old")

        val recovered = store(target).recover()

        assertEquals("valid:old", recovered?.readText())
        assertFalse(backup.exists())
    }

    @Test
    fun successfulWriteReplacesValueAndLeavesNoRecoveryArtifacts() {
        val target = target()
        target.writeText("valid:old")
        val store = store(target)

        store.write("valid:new".toByteArray())

        assertEquals("valid:new", target.readText())
        assertTrue(target.isFile)
        assertFalse(File(target.parentFile, "${target.name}.new").exists())
        assertFalse(File(target.parentFile, "${target.name}.bak").exists())
    }

    private fun target() = File(folder.newFolder(), "drive-sync-secret-v1.bin")

    private fun store(target: File) = CrashSafeSecretFile(target) { candidate ->
        candidate.isFile && candidate.readText().startsWith("valid:")
    }
}
