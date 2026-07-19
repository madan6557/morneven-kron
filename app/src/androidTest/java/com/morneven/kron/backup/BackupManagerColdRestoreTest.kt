package com.morneven.kron.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.morneven.kron.data.KronDatabase
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackupManagerColdRestoreTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        root = File(base.cacheDir, "cold-restore-${UUID.randomUUID()}").apply { mkdirs() }
        context = IsolatedRestoreContext(base, root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun committedSwapKeepsRollbackDataUntilNextColdStart() {
        val liveDatabase = context.getDatabasePath(KronDatabase.DATABASE_NAME)
        liveDatabase.parentFile!!.mkdirs()
        liveDatabase.writeBytes("database-old".toByteArray())
        File(liveDatabase.path + "-wal").writeBytes("wal-old".toByteArray())
        File(liveDatabase.path + "-shm").writeBytes("shm-old".toByteArray())
        File(context.noBackupFilesDir, "receipts").apply {
            mkdirs()
            File(this, "receiptold.kat").writeBytes("receipt-old".toByteArray())
        }

        val pending = File(context.filesDir, "pending-restore-v2").apply { mkdirs() }
        val stagedDatabase = File(pending, "database.sqlite").apply {
            writeBytes("database-new".toByteArray())
        }
        val stagedReceipts = File(pending, "receipts").apply {
            mkdirs()
            File(this, "receiptnew.kat").writeBytes("receipt-new".toByteArray())
        }
        File(pending, "ready.json").writeText(
            JSONObject()
                .put("format", 2)
                .put("databaseSha256", sha256(stagedDatabase))
                .put("attachmentCount", 1)
                .put("receiptsSha256", directorySha256(stagedReceipts))
                .toString(),
        )

        BackupManager.applyPendingRestore(context)

        assertArrayEquals("database-new".toByteArray(), liveDatabase.readBytes())
        assertFalse(File(liveDatabase.path + "-wal").exists())
        assertFalse(File(liveDatabase.path + "-shm").exists())
        assertTrue(File(context.noBackupFilesDir, "receipts/receiptnew.kat").isFile)
        assertTrue(File(pending, "committed").isFile)
        assertTrue(File(liveDatabase.parentFile, ".${liveDatabase.name}.restore-old-v2").isFile)

        BackupManager.applyPendingRestore(context)

        assertFalse(pending.exists())
        assertFalse(File(liveDatabase.parentFile, ".${liveDatabase.name}.restore-old-v2").exists())
        assertFalse(File(context.noBackupFilesDir, ".receipts.restore-old-v2").exists())
        assertArrayEquals("database-new".toByteArray(), liveDatabase.readBytes())
    }

    @Test
    fun incompleteInstalledSwapRollsBackDatabaseSidecarsAndReceiptDirectory() {
        val liveDatabase = context.getDatabasePath(KronDatabase.DATABASE_NAME)
        val databaseParent = liveDatabase.parentFile!!.apply { mkdirs() }
        val oldDatabase = File(databaseParent, ".${liveDatabase.name}.restore-old-v2").apply {
            writeBytes("database-old".toByteArray())
        }
        val oldWal = File(databaseParent, ".${liveDatabase.name}-wal.restore-old-v2").apply {
            writeBytes("wal-old".toByteArray())
        }
        val oldShm = File(databaseParent, ".${liveDatabase.name}-shm.restore-old-v2").apply {
            writeBytes("shm-old".toByteArray())
        }
        liveDatabase.writeBytes("database-new".toByteArray())

        val liveReceipts = File(context.noBackupFilesDir, "receipts").apply {
            mkdirs()
            File(this, "receiptnew.kat").writeBytes("receipt-new".toByteArray())
        }
        val oldReceipts = File(context.noBackupFilesDir, ".receipts.restore-old-v2").apply {
            mkdirs()
            File(this, "receiptold.kat").writeBytes("receipt-old".toByteArray())
        }
        val pending = File(context.filesDir, "pending-restore-v2").apply { mkdirs() }
        File(pending, "swap.json").writeText(
            JSONObject()
                .put("hadDatabase", true)
                .put("hadWal", true)
                .put("hadShm", true)
                .put("hadReceipts", true)
                .put("databaseSha256", sha256(oldDatabase))
                .put("receiptsSha256", directorySha256(oldReceipts))
                .toString(),
        )
        File(pending, "new_installed").writeText("crash")

        BackupManager.applyPendingRestore(context)

        assertArrayEquals("database-old".toByteArray(), liveDatabase.readBytes())
        assertArrayEquals("wal-old".toByteArray(), File(liveDatabase.path + "-wal").readBytes())
        assertArrayEquals("shm-old".toByteArray(), File(liveDatabase.path + "-shm").readBytes())
        assertTrue(File(liveReceipts, "receiptold.kat").isFile)
        assertFalse(File(liveReceipts, "receiptnew.kat").exists())
        assertFalse(pending.exists())
        assertFalse(oldDatabase.exists())
        assertFalse(oldWal.exists())
        assertFalse(oldShm.exists())
        assertFalse(oldReceipts.exists())
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun directorySha256(directory: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        directory.listFiles().orEmpty().sortedBy(File::getName).forEach { entry ->
            digest.update(entry.name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(entry.readBytes())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class IsolatedRestoreContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }

        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }

        override fun getDatabasePath(name: String): File = File(root, "databases/$name")
    }
}
