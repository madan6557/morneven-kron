package com.morneven.kron.team

import android.content.Context
import android.os.StatFs
import com.morneven.kron.BuildConfig
import com.morneven.kron.backup.BackupManager
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.sync.AesGcmDriveSnapshotCryptor
import com.morneven.kron.sync.DriveSnapshotManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps one verified, encrypted local Team branch before an explicit remote replacement. */
@Singleton
class TeamConflictRecoveryStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val backupManager: BackupManager,
    private val cryptor: TeamSnapshotCryptor,
) {
    suspend fun save(
        accountId: Long,
        teamId: String,
        localHeadSnapshotId: String?,
        generation: Long,
        teamKey: ByteArray,
    ) = withContext(Dispatchers.IO) {
        require(teamId.isNotBlank() && generation >= 0) { "Metadata recovery konflik Team tidak valid" }
        purgeExpired()
        var payload = ByteArray(0)
        var envelope = ByteArray(0)
        try {
            payload = backupManager.createTeamSnapshotPayload(accountId)
            val manifest = DriveSnapshotManifest(
                protocolVersion = 2,
                datasetId = teamId,
                snapshotId = "local-conflict-${UUID.randomUUID()}",
                parentSnapshotId = localHeadSnapshotId,
                parentSnapshotIds = listOfNotNull(localHeadSnapshotId),
                generation = generation,
                sourceDeviceId = "local-conflict-recovery",
                schemaVersion = KronDatabase.SCHEMA_VERSION,
                minimumAppVersionCode = BuildConfig.VERSION_CODE,
                createdAtEpochMillis = System.currentTimeMillis(),
                payloadSha256 = AesGcmDriveSnapshotCryptor.sha256(payload),
            )
            envelope = cryptor.encrypt(manifest, payload, teamKey)
            val target = recoveryFile(teamId)
            val stat = StatFs(context.noBackupFilesDir.absolutePath)
            require(stat.availableBytes >= envelope.size.toLong() + MIN_FREE_BYTES) {
                "Ruang penyimpanan tidak cukup untuk recovery konflik Team"
            }
            writeAtomically(target, envelope)
            val opened = cryptor.decrypt(target.readBytes(), teamKey)
            try {
                require(opened.manifest.datasetId == teamId && opened.manifest.snapshotId == manifest.snapshotId) {
                    "Recovery konflik Team tidak dapat diverifikasi"
                }
            } finally {
                opened.payload.fill(0)
            }
        } finally {
            payload.fill(0)
            envelope.fill(0)
        }
    }

    private fun recoveryFile(teamId: String): File = File(root, "${sha256(teamId)}.recovery")

    private fun purgeExpired() {
        val threshold = System.currentTimeMillis() - RETENTION_MILLIS
        root.listFiles()?.filter { it.isFile && it.lastModified() < threshold }?.forEach { it.delete() }
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        root.mkdirs()
        val temporary = File(root, "${target.name}.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        try {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private val root: File get() = File(context.noBackupFilesDir, DIRECTORY)

    private companion object {
        const val DIRECTORY = "team-conflict-recovery"
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
        const val MIN_FREE_BYTES = 8L * 1024 * 1024
    }
}
