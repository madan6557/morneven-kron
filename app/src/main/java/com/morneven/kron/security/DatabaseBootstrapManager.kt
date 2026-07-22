package com.morneven.kron.security

import android.content.Context
import com.morneven.kron.data.KronDatabase
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

enum class DatabaseBootstrapState {
    CHECKING,
    BACKUP_REQUIRED,
    MIGRATING,
    READY,
    RECOVERY_REQUIRED,
}

data class DatabaseBootstrapStatus(
    val state: DatabaseBootstrapState,
    val inspection: DatabaseEncryptionManager.DatabaseInspection,
    val message: String? = null,
)

class DatabaseBootstrapManager(private val context: Context) {
    private val keyManager = DatabaseKeyManager(context)
    private val encryption = DatabaseEncryptionManager(context, keyManager)
    private val database = context.getDatabasePath(KronDatabase.DATABASE_NAME)
    private val securityDirectory = File(context.noBackupFilesDir, "security")
    private val backupMarker = File(securityDirectory, "pre-upgrade-backup-v1.5.0.done")
    private val successfulLaunches = File(securityDirectory, "database-bootstrap-v3.launches")

    fun inspect(): DatabaseBootstrapStatus {
        val inspection = encryption.inspectPrimaryDatabase(database)
        if (!inspection.exists) {
            val blockingEnvelope = inspection.keyEnvelopePresent && !inspection.keyInitializationPending
            return if (inspection.hasRecoveryArtifacts || inspection.keyProfilePresent || blockingEnvelope) {
                DatabaseBootstrapStatus(
                    DatabaseBootstrapState.RECOVERY_REQUIRED,
                    inspection,
                    "Database utama tidak ditemukan sementara artefak data lama masih tersedia.",
                )
            } else {
                DatabaseBootstrapStatus(DatabaseBootstrapState.MIGRATING, inspection)
            }
        }
        val recognized = inspection.resolvedMode != null || inspection.isPlaintext || inspection.acceptsEmptyKey
        if (!recognized) {
            return DatabaseBootstrapStatus(
                DatabaseBootstrapState.RECOVERY_REQUIRED,
                inspection,
                "Database tidak cocok dengan seluruh format kunci KRON yang pernah dirilis.",
            )
        }
        if (!hasVerifiedExternalBackup()) {
            return DatabaseBootstrapStatus(DatabaseBootstrapState.BACKUP_REQUIRED, inspection)
        }
        return DatabaseBootstrapStatus(DatabaseBootstrapState.MIGRATING, inspection)
    }

    fun markExternalBackupVerified() {
        securityDirectory.mkdirs()
        val temporary = File(securityDirectory, "${backupMarker.name}.new")
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(BACKUP_MARKER_MAGIC)
                output.flush()
                output.fd.sync()
            }
            atomicReplace(temporary, backupMarker)
        } finally {
            temporary.delete()
        }
    }

    fun markFreshInstallValidated() = markExternalBackupVerified()

    fun recordSuccessfulColdLaunch() {
        securityDirectory.mkdirs()
        val previous = readSuccessfulLaunches()
        val next = (previous + 1).coerceAtMost(REQUIRED_VERIFIED_LAUNCHES)
        val temporary = File(securityDirectory, "${successfulLaunches.name}.new")
        try {
            FileOutputStream(temporary, false).use { output ->
                DataOutputStream(output).use { data ->
                    data.write(LAUNCH_MAGIC)
                    data.writeInt(next)
                    data.flush()
                    output.fd.sync()
                }
            }
            atomicReplace(temporary, successfulLaunches)
            if (next >= REQUIRED_VERIFIED_LAUNCHES) {
                encryption.discardValidatedPreUpgradeCopy(database)
            }
        } finally {
            temporary.delete()
        }
    }

    fun hasVerifiedExternalBackup(): Boolean = backupMarker.isFile && runCatching {
        backupMarker.readBytes().contentEquals(BACKUP_MARKER_MAGIC)
    }.getOrDefault(false)

    private fun readSuccessfulLaunches(): Int {
        if (!successfulLaunches.isFile) return 0
        return runCatching {
            successfulLaunches.inputStream().buffered().use { input ->
                DataInputStream(input).use { data ->
                    val magic = ByteArray(LAUNCH_MAGIC.size).also(data::readFully)
                    require(magic.contentEquals(LAUNCH_MAGIC))
                    val count = data.readInt()
                    require(data.read() == -1)
                    count.coerceIn(0, REQUIRED_VERIFIED_LAUNCHES)
                }
            }
        }.getOrDefault(0)
    }

    private fun atomicReplace(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.inputStream().use { input ->
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
        }
    }

    companion object {
        private const val REQUIRED_VERIFIED_LAUNCHES = 2
        private val BACKUP_MARKER_MAGIC = "KRONBACKUP150".toByteArray(Charsets.US_ASCII)
        private val LAUNCH_MAGIC = "KRONBOOT3".toByteArray(Charsets.US_ASCII)
    }
}
