package com.morneven.kron.team

import android.content.Context
import com.morneven.kron.data.KronDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal class TeamAtomicSwap private constructor(private val context: Context) {
    private val swapDir: File get() = File(context.filesDir, SWAP_DIR_NAME)
    private val stagingDb: File get() = File(swapDir, "database.db")
    private val transactionFile: File get() = File(swapDir, TRANSACTION_FILE)
    private val readyFile: File get() = File(swapDir, READY_FILE)
    private val liveDb: File get() = context.getDatabasePath(KronDatabase.DATABASE_NAME)
    private val liveDbParent: File get() = requireNotNull(liveDb.parentFile)
    private val newDb: File get() = File(liveDbParent, ".${liveDb.name}.swap-new")
    private val oldDb: File get() = File(liveDbParent, ".${liveDb.name}.swap-old")

    fun stageReplace(newDatabase: File) {
        swapDir.mkdirs()
        copyFile(newDatabase, stagingDb)
        copySidecars(newDatabase, stagingDb)
        writeSynced(readyFile, "$READY_VERSION:${sha256File(stagingDb)}")
    }

    fun applySwap() {
        if (!swapDir.exists()) return
        if (readyFile.exists()) {
            val metadata = runCatching(::readSwapMetadata).getOrElse {
                writeSynced(transactionFile, "aborted:invalid-ready-file")
                deleteSwapDir()
                return
            }
            try {
                oldDb.delete()
                deleteSidecars(oldDb)
                copyFile(stagingDb, newDb)
                copySidecars(stagingDb, newDb)
                require(sha256File(newDb) == metadata.dbSha256) { "Database staging swap tidak cocok" }
                moveDatabaseSet(liveDb, oldDb)
                moveDatabaseSet(newDb, liveDb)
                require(liveDb.exists()) { "Database live setelah swap tidak ditemukan" }
                require(sha256File(liveDb) == metadata.dbSha256) { "Database live setelah swap tidak cocok" }
                writeSynced(transactionFile, "committed:${metadata.dbSha256}")
                deleteSwapDir()
            } catch (e: Exception) {
                val oldExists = oldDb.exists()
                if (oldExists) {
                    liveDb.delete()
                    deleteSidecars(liveDb)
                    copyFile(oldDb, liveDb)
                    copySidecars(oldDb, liveDb)
                }
                writeSynced(transactionFile, "failed")
                throw e
            }
        } else {
            writeSynced(transactionFile, "aborted:no-ready-file")
            deleteSwapDir()
        }
    }

    private fun readSwapMetadata(): SwapMetadata {
        val value = readyFile.readText().trim()
        require(value.startsWith("$READY_VERSION:")) { "Staging Team lama tidak boleh diaktifkan" }
        val sha256 = value.substringAfter(':')
        require(sha256.matches(SHA256_REGEX)) { "SHA-256 staging swap tidak valid" }
        return SwapMetadata(sha256)
    }

    private fun copyFile(src: File, dst: File) {
        dst.parentFile?.mkdirs()
        src.inputStream().use { input ->
            FileOutputStream(dst, false).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        dst.setLastModified(src.lastModified())
    }

    private fun writeSynced(file: File, value: String) {
        file.parentFile?.mkdirs()
        FileOutputStream(file, false).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun copySidecars(src: File, dst: File) {
        File(src.path + "-wal").takeIf { it.exists() }?.let { copyFile(it, File(dst.path + "-wal")) }
        File(src.path + "-shm").takeIf { it.exists() }?.let { copyFile(it, File(dst.path + "-shm")) }
    }

    private fun deleteSidecars(base: File) {
        File(base.path + "-wal").delete()
        File(base.path + "-shm").delete()
    }

    private fun moveDatabaseSet(source: File, target: File) {
        moveFile(source, target)
        listOf("-wal", "-shm").forEach { suffix ->
            val sidecar = File(source.path + suffix)
            if (sidecar.exists()) moveFile(sidecar, File(target.path + suffix))
        }
    }

    private fun moveFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
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
        private const val READY_VERSION = 2
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")

        fun applyPendingSwap(context: Context) {
            TeamAtomicSwap(context).applySwap()
        }

        fun stageReplaceForRestart(context: Context, database: File) {
            TeamAtomicSwap(context).stageReplace(database)
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
