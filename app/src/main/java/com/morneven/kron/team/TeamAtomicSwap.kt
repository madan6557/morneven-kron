package com.morneven.kron.team

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import android.system.OsConstants
import com.morneven.kron.backup.TeamGraphImporter
import com.morneven.kron.backup.TeamImportMetadata
import com.morneven.kron.data.KronDatabase
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal class TeamAtomicSwap private constructor(private val context: Context) {
    private val swapDir: File get() = File(context.filesDir, SWAP_DIR_NAME)
    private val stagingDb: File get() = File(swapDir, "database.db")
    private val transactionFile: File get() = File(swapDir, TRANSACTION_FILE)
    private val readyFile: File get() = File(swapDir, READY_FILE)
    private val liveDb: File get() = context.getDatabasePath(KronDatabase.DATABASE_NAME)
    private val liveDbParent: File get() = requireNotNull(liveDb.parentFile)
    private val newDb: File get() = File(liveDbParent, ".${liveDb.name}.swap-new")
    private val oldDb: File get() = File(liveDbParent, ".${liveDb.name}.swap-old")
    private val oldWal: File get() = File(liveDbParent, ".${liveDb.name}-wal.swap-old")
    private val oldShm: File get() = File(liveDbParent, ".${liveDb.name}-shm.swap-old")

    suspend fun stageMerge(
        sourceDb: File,
        metadata: TeamImportMetadata,
    ) {
        swapDir.mkdirs()
        copyFile(liveDb, stagingDb)
        copySidecars(liveDb, stagingDb)
        TeamGraphImporter.merge(stagingDb, sourceDb, metadata)
        val sha256 = sha256File(stagingDb)
        readyFile.writeText(sha256)
    }

    fun stageReplace(newDatabase: File) {
        swapDir.mkdirs()
        copyFile(newDatabase, stagingDb)
        copySidecars(newDatabase, stagingDb)
        val sha256 = sha256File(stagingDb)
        readyFile.writeText(sha256)
    }

    fun applySwap() {
        if (!swapDir.exists()) return
        if (readyFile.exists()) {
            val metadata = readSwapMetadata()
            try {
                deleteSidecars(oldDb)
                copyFile(liveDb, oldDb)
                copySidecars(liveDb, oldDb)
                copyFile(stagingDb, newDb)
                copySidecars(stagingDb, newDb)
                require(sha256File(newDb) == metadata.dbSha256) { "Database staging swap tidak cocok" }
                swapSidecars(newDb, liveDb)
                require(liveDb.exists()) { "Database live setelah swap tidak ditemukan" }
                require(sha256File(liveDb) == metadata.dbSha256) { "Database live setelah swap tidak cocok" }
                transactionFile.writeText("committed:${metadata.dbSha256}")
                deleteSwapDir()
            } catch (e: Exception) {
                val oldExists = oldDb.exists()
                if (oldExists) {
                    deleteSidecars(liveDb)
                    copyFile(oldDb, liveDb)
                    copySidecars(oldDb, liveDb)
                }
                transactionFile.writeText("failed:${e.message}")
                throw e
            }
        } else {
            transactionFile.writeText("aborted:no-ready-file")
            deleteSwapDir()
        }
    }

    private fun readSwapMetadata(): SwapMetadata {
        val sha256 = readyFile.readText().trim()
        require(sha256.matches(SHA256_REGEX)) { "SHA-256 staging swap tidak valid" }
        return SwapMetadata(sha256)
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        src.inputStream().use { input -> dst.outputStream().use { output -> input.copyTo(output) } }
        dst.setLastModified(src.lastModified())
    }

    private fun copySidecars(src: File, dst: File) {
        File(src.path + "-wal").takeIf { it.exists() }?.let { copyFile(it, File(dst.path + "-wal")) }
        File(src.path + "-shm").takeIf { it.exists() }?.let { copyFile(it, File(dst.path + "-shm")) }
    }

    private fun deleteSidecars(base: File) {
        File(base.path + "-wal").delete()
        File(base.path + "-shm").delete()
    }

    private fun swapSidecars(newBase: File, liveBase: File) {
        val wal = File(newBase.path + "-wal")
        val shm = File(newBase.path + "-shm")
        if (wal.exists()) wal.renameTo(File(liveBase.path + "-wal"))
        if (shm.exists()) shm.renameTo(File(liveBase.path + "-shm"))
    }

    private fun deleteSwapDir() {
        stagingDb.delete()
        deleteSidecars(stagingDb)
        readyFile.delete()
        transactionFile.delete()
        swapDir.delete()
    }

    private data class SwapMetadata(val dbSha256: String)

    companion object {
        private const val SWAP_DIR_NAME = "team-swap"
        private const val TRANSACTION_FILE = "transaction"
        private const val READY_FILE = "ready"
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")

        fun applyPendingSwap(context: Context) {
            TeamAtomicSwap(context).applySwap()
        }

        suspend fun stageAndSwap(
            context: Context,
            prepared: suspend TeamAtomicSwap.() -> Unit,
        ) {
            val swap = TeamAtomicSwap(context)
            swap.prepared()
            swap.applySwap()
        }

        private fun sha256File(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
