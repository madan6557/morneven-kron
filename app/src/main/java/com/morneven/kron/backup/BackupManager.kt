package com.morneven.kron.backup

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.ReceiptEntity
import com.morneven.kron.security.DatabaseEncryptionManager
import com.morneven.kron.security.EncryptedAttachmentStore
import com.morneven.kron.security.SnapshotOperationLock
import com.morneven.kron.security.SqlCipherLibrary
import com.morneven.kron.sync.ConflictDataset
import com.morneven.kron.sync.ConflictEventRecord
import com.morneven.kron.sync.ConflictMutableRecord
import com.morneven.kron.sync.ConflictPreview
import com.morneven.kron.sync.ConflictPreviewBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: KronDatabase,
    private val attachmentStore: EncryptedAttachmentStore,
    private val databaseEncryption: DatabaseEncryptionManager,
    private val snapshotOperationLock: SnapshotOperationLock,
) {
    suspend fun export(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        require(password.size >= MIN_PASSWORD_LENGTH) { "Password backup minimal 12 karakter" }
        try {
            snapshotOperationLock.withLock {
                val staged = File(context.cacheDir, "backup-export-${UUID.randomUUID()}.kronbackup")
                try {
                    writeEncryptedBackup(staged, password)
                    verifyBackupStaging(staged)
                    val output = context.contentResolver.openOutputStream(uri, "w")
                        ?: error("Tidak dapat membuka tujuan backup")
                    output.buffered().use { destination ->
                        staged.inputStream().buffered().use { source -> source.copyTo(destination) }
                        destination.flush()
                    }
                } finally {
                    staged.delete()
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun writeEncryptedBackup(target: File, password: CharArray) {
        target.delete()
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        FileOutputStream(target).buffered().use { rawOutput ->
            val data = DataOutputStream(rawOutput)
            data.write(MAGIC_V3)
            data.write(salt)
            data.write(nonce)
            data.writeInt(PBKDF2_ITERATIONS)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                deriveKey(password, salt, PBKDF2_ITERATIONS),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            CipherOutputStream(data, cipher).use { encrypted ->
                writePortableSnapshot(encrypted)
            }
        }
    }

    private fun verifyBackupStaging(file: File) {
        require(file.exists() && file.length() > MAGIC_V3.size + SALT_BYTES + NONCE_BYTES + Int.SIZE_BYTES) {
            "Backup staging tidak lengkap"
        }
        val magic = DataInputStream(file.inputStream().buffered()).use { input -> input.readExact(MAGIC_V3.size) }
        require(magic.contentEquals(MAGIC_V3)) { "Header backup staging tidak valid" }
    }

    suspend fun createPortableSnapshotPayload(): ByteArray = withContext(Dispatchers.IO) {
        snapshotOperationLock.withLock {
            ByteArrayOutputStream().use { output ->
                writePortableSnapshot(output)
                require(output.size().toLong() <= MAX_SYNC_PAYLOAD_BYTES) { "Snapshot terlalu besar untuk sinkronisasi Drive" }
                output.toByteArray()
            }
        }
    }

    suspend fun previewPortableSnapshotPayload(
        payload: ByteArray,
        localSnapshotId: String?,
        remoteSnapshotId: String,
    ): ConflictPreview = withContext(Dispatchers.IO) {
        require(payload.isNotEmpty() && payload.size.toLong() <= MAX_SYNC_PAYLOAD_BYTES) {
            "Snapshot Drive tidak valid atau terlalu besar"
        }
        snapshotOperationLock.withLock {
            val workspace = File(context.cacheDir, "conflict-preview-${UUID.randomUUID()}")
            check(workspace.mkdirs()) { "Staging Pusat Konflik tidak dapat dibuat" }
            val validationFile = context.getDatabasePath(VALIDATION_DATABASE_NAME)
            try {
                val packageFile = File(workspace, "package.zip")
                packageFile.outputStream().use { it.write(payload) }
                val extracted = extractPackage(packageFile, workspace)
                val format = extracted.manifest.optInt("format", -1)
                require(format == LEGACY_FORMAT || format == CURRENT_FORMAT) { "Versi format backup tidak didukung" }
                verifyExtractedPackage(extracted, format)
                deleteDatabaseFiles(validationFile)
                extracted.database.copyTo(validationFile, overwrite = true)
                migrateAndValidateCandidate(validationFile)
                validateDatabase(validationFile)

                val local = readConflictDataset(localSnapshotId) { sql ->
                    database.openHelper.readableDatabase.query(sql)
                }
                val remote = SQLiteDatabase.openDatabase(
                    validationFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { candidate ->
                    readConflictDataset(remoteSnapshotId) { sql -> candidate.rawQuery(sql, null) }
                }
                ConflictPreviewBuilder.build(local, remote)
            } finally {
                deleteDatabaseFiles(validationFile)
                deleteScopedDirectory(workspace, context.cacheDir)
            }
        }
    }

    suspend fun applyPortableSnapshotPayloadAtomically(
        payload: ByteArray,
        datasetId: String,
        generation: Long,
        parentSnapshotId: String?,
        snapshotId: String,
        accountSubject: String,
        accountEmail: String,
    ) = withContext(Dispatchers.IO) {
        require(payload.isNotEmpty() && payload.size.toLong() <= MAX_SYNC_PAYLOAD_BYTES) {
            "Snapshot Drive tidak valid atau terlalu besar"
        }
        require(
            datasetId.isNotBlank() && snapshotId.isNotBlank() && generation >= 0 &&
                accountSubject.isNotBlank() && accountEmail.isNotBlank(),
        ) {
            "Metadata snapshot Drive tidak valid"
        }
        snapshotOperationLock.withLock {
            val workspace = File(context.cacheDir, "restore-${UUID.randomUUID()}")
            workspace.mkdirs()
            try {
                val packageFile = File(workspace, "package.zip")
                packageFile.outputStream().use { it.write(payload) }
                stagePortablePackage(
                    packageFile,
                    workspace,
                    preserveTargetSyncAccount = true,
                    driveMetadata = DriveRestoreMetadata(
                        datasetId = datasetId,
                        generation = generation,
                        parentSnapshotId = parentSnapshotId,
                        snapshotId = snapshotId,
                        accountSubject = accountSubject,
                        accountEmail = accountEmail,
                    ),
                )
            } finally {
                deleteScopedDirectory(workspace, context.cacheDir)
            }
        }
    }

    suspend fun applyPortableSnapshotDirectly(
        payload: ByteArray,
        datasetId: String,
        generation: Long,
        parentSnapshotId: String?,
        snapshotId: String,
        accountSubject: String,
        accountEmail: String,
    ) = withContext(Dispatchers.IO) {
        require(payload.isNotEmpty() && payload.size.toLong() <= MAX_SYNC_PAYLOAD_BYTES) {
            "Snapshot Drive tidak valid atau terlalu besar"
        }
        require(
            datasetId.isNotBlank() && snapshotId.isNotBlank() && generation >= 0 &&
                accountSubject.isNotBlank() && accountEmail.isNotBlank(),
        ) {
            "Metadata snapshot Drive tidak valid"
        }
        snapshotOperationLock.withLock {
            val workspace = File(context.cacheDir, "restore-${UUID.randomUUID()}")
            workspace.mkdirs()
            try {
                val packageFile = File(workspace, "package.zip")
                packageFile.outputStream().use { it.write(payload) }
                val extracted = extractPackage(packageFile, workspace)
                val format = extracted.manifest.optInt("format", -1)
                require(format == LEGACY_FORMAT || format == CURRENT_FORMAT) { "Versi format backup tidak didukung" }
                verifyExtractedPackage(extracted, format)
                val validationFile = context.getDatabasePath(VALIDATION_DATABASE_NAME)
                deleteDatabaseFiles(validationFile)
                try {
                    extracted.database.copyTo(validationFile, overwrite = true)
                    migrateAndValidateCandidate(validationFile)
                    normalizeSyncState(
                        validationFile, extracted.manifest, preserveTargetAccount = true,
                        driveMetadata = DriveRestoreMetadata(
                            datasetId = datasetId, generation = generation,
                            parentSnapshotId = parentSnapshotId, snapshotId = snapshotId,
                            accountSubject = accountSubject, accountEmail = accountEmail,
                        ),
                    )
                    databaseEncryption.prepareValidatedRestoreKey()
                    installReceiptPayloadsDirect(validationFile, extracted.attachments)
                    validateDatabase(validationFile)
                    val liveDb = database.openHelper.writableDatabase
                    liveDb.execSQL(
                        "ATTACH DATABASE ? AS restore_db KEY ''",
                        arrayOf(validationFile.absolutePath),
                    )
                    val appendOnlyTables = listOf(
                        "activity_events", "cash_journal_lines", "budget_journal_lines",
                        "transaction_splits", "audit_snapshots", "ledger_lines",
                        "journal_seals", "evidence_keys", "team_invitation_uses", "team_event_proofs",
                    )
                    for (table in appendOnlyTables) {
                        liveDb.execSQL("DROP TRIGGER IF EXISTS append_only_${table}_delete")
                        liveDb.execSQL("DROP TRIGGER IF EXISTS append_only_${table}_update")
                    }
                    liveDb.execSQL("DROP TRIGGER IF EXISTS append_only_receipts_update")
                    liveDb.execSQL("DROP TRIGGER IF EXISTS append_only_receipts_delete")
                    val guardedTables = listOf(
                        "accounts", "categories", "portfolios",
                        "budget_periods", "allocations",
                        "portfolio_allocation_templates", "activity_events",
                        "recurring_rules", "receipts",
                        "cash_journal_lines", "budget_journal_lines",
                        "transaction_splits", "recurring_occurrences",
                        "audit_snapshots", "ledger_accounts",
                        "ledger_lines", "journal_seals", "evidence_keys",
                        "team_workspaces", "team_members", "team_invitation_uses", "team_event_proofs",
                    )
                    for (table in guardedTables) {
                        for (op in listOf("INSERT", "UPDATE", "DELETE")) {
                            liveDb.execSQL(
                                "DROP TRIGGER IF EXISTS sync_write_guard_${table}_${op.lowercase()}",
                            )
                        }
                    }
                    val cursor = liveDb.query(
                        """SELECT name FROM restore_db.sqlite_master
                           WHERE type='table' AND name NOT LIKE 'room_%'
                           AND name != 'sync_state' AND name != 'android_metadata'""",
                    )
                    val tables = mutableListOf<String>()
                    while (cursor.moveToNext()) tables.add(cursor.getString(0))
                    cursor.close()
                    val dao = database.kronDao()
                    for (table in tables) {
                        dao.executeRaw(
                            androidx.sqlite.db.SimpleSQLiteQuery("DELETE FROM $table"),
                        )
                        dao.executeRaw(
                            androidx.sqlite.db.SimpleSQLiteQuery(
                                "INSERT INTO $table SELECT * FROM restore_db.$table",
                            ),
                        )
                    }
                    for (table in tables) {
                        try {
                            liveDb.execSQL(
                                "UPDATE $table SET _rowid_ = _rowid_ WHERE _rowid_ IN (SELECT _rowid_ FROM $table LIMIT 1)",
                            )
                        } catch (_: Exception) {
                        }
                    }
                    database.invalidationTracker.refreshAsync()
                    recreateAppendOnlyTriggers(liveDb)
                    recreateSyncWriteGuardTriggers(liveDb)
                    liveDb.execSQL("DETACH DATABASE restore_db")
                } finally {
                    deleteDatabaseFiles(validationFile)
                }
            } finally {
                deleteScopedDirectory(workspace, context.cacheDir)
            }
        }
    }

    suspend fun stageRestore(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        require(password.size >= LEGACY_MIN_PASSWORD_LENGTH) { "Password backup minimal 8 karakter" }
        try {
            snapshotOperationLock.withLock {
                val workspace = File(context.cacheDir, "restore-${UUID.randomUUID()}")
                workspace.mkdirs()
                try {
                    val packageFile = File(workspace, "package.zip")
                    decryptPackage(uri, password, packageFile)
                    stagePortablePackage(packageFile, workspace, preserveTargetSyncAccount = false)
                } finally {
                    deleteScopedDirectory(workspace, context.cacheDir)
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    private suspend fun writePortableSnapshot(output: OutputStream) {
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val databaseFile = context.getDatabasePath(KronDatabase.DATABASE_NAME)
        require(databaseFile.exists()) { "Database belum tersedia" }
        val portable = File(context.cacheDir, "backup-portable-${UUID.randomUUID()}.db")
        try {
            databaseEncryption.exportPlaintext(databaseFile, portable)
            val databaseBytes = portable.length()
            require(databaseBytes in 1..MAX_DATABASE_BYTES) { "Ukuran database tidak valid" }
            val databaseSha = sha256(portable)
            val attachments = collectAttachments(database.kronDao().allReceipts())
            val syncState = database.kronDao().syncState()
            val schemaVersion = database.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
                require(cursor.moveToFirst())
                cursor.getInt(0)
            }
            val manifest = JSONObject()
                .put("format", CURRENT_FORMAT)
                .put("app", "KRON")
                .put("createdAt", Instant.now().toString())
                .put("schemaVersion", schemaVersion)
                .put("databaseSha256", databaseSha)
                .put("databaseBytes", databaseBytes)
                .put("attachmentCount", attachments.size)
                .put("datasetId", syncState?.datasetId ?: JSONObject.NULL)
                .put("generation", syncState?.localGeneration ?: 0)
            val checksumIndex = buildString {
                append(DATABASE_ENTRY).append('\t').append(databaseSha).append('\t').append(databaseBytes).append('\n')
                attachments.forEach { attachment ->
                    append(attachment.entryName).append('\t').append(attachment.sha256)
                        .append('\t').append(attachment.byteSize).append('\n')
                }
            }
            ZipOutputStream(output).use { zip ->
                zip.writeEntry(MANIFEST_ENTRY, manifest.toString().toByteArray(Charsets.UTF_8))
                zip.writeEntry(CHECKSUM_ENTRY, checksumIndex.toByteArray(Charsets.UTF_8))
                zip.putNextEntry(stableZipEntry(DATABASE_ENTRY))
                portable.inputStream().use { input -> input.copyToWithLimit(zip, MAX_DATABASE_BYTES) }
                zip.closeEntry()
                attachments.forEach { attachment ->
                    zip.putNextEntry(stableZipEntry(attachment.entryName))
                    if (attachment.receipt.encryptionVersion == EncryptedAttachmentStore.ENCRYPTION_VERSION) {
                        attachmentStore.decrypt(attachment.file, zip)
                    } else {
                        attachment.file.inputStream().use { input ->
                            input.copyToWithLimit(zip, EncryptedAttachmentStore.MAX_ATTACHMENT_BYTES)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } finally {
            portable.delete()
        }
    }

    private suspend fun stagePortablePackage(
        packageFile: File,
        workspace: File,
        preserveTargetSyncAccount: Boolean,
        driveMetadata: DriveRestoreMetadata? = null,
    ) {
        val extracted = extractPackage(packageFile, workspace)
        val format = extracted.manifest.optInt("format", -1)
        require(format == LEGACY_FORMAT || format == CURRENT_FORMAT) { "Versi format backup tidak didukung" }
        verifyExtractedPackage(extracted, format)
        val validationFile = context.getDatabasePath(VALIDATION_DATABASE_NAME)
        deleteDatabaseFiles(validationFile)
        try {
            extracted.database.copyTo(validationFile, overwrite = true)
            migrateAndValidateCandidate(validationFile)
            normalizeSyncState(validationFile, extracted.manifest, preserveTargetSyncAccount, driveMetadata)
            databaseEncryption.prepareValidatedRestoreKey()

            val pending = File(context.filesDir, PENDING_DIRECTORY)
            deleteScopedDirectory(pending, context.filesDir)
            val pendingReceipts = File(pending, "receipts").apply { mkdirs() }
            installReceiptPayloads(validationFile, extracted.attachments, pendingReceipts)
            validateDatabase(validationFile)
            pending.mkdirs()
            val pendingDatabase = File(pending, DATABASE_ENTRY)
            databaseEncryption.encryptPortableDatabase(validationFile, pendingDatabase)
            val ready = JSONObject()
                .put("format", CURRENT_FORMAT)
                .put("databaseSha256", sha256(pendingDatabase))
                .put("attachmentCount", extracted.attachments.size)
                .put("receiptsSha256", directorySha256Static(pendingReceipts))
                .put("stagedAt", Instant.now().toString())
            syncDirectoryStatic(requireNotNull(pending.parentFile))
            writeTextAndSync(File(pending, READY_FILE), ready.toString())
        } finally {
            deleteDatabaseFiles(validationFile)
        }
    }

    private suspend fun normalizeSyncState(
        candidate: File,
        manifest: JSONObject,
        preserveTargetAccount: Boolean,
        driveMetadata: DriveRestoreMetadata?,
    ) {
        val targetState = database.kronDao().syncState()
        val targetDeviceId = targetState?.deviceId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val sqlite = SQLiteDatabase.openDatabase(candidate.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        sqlite.use { db ->
            val source = db.rawQuery(
                "SELECT datasetId, localGeneration FROM sync_state WHERE id = 1",
                null,
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) to cursor.getLong(1) else null
            }
            val datasetId = driveMetadata?.datasetId
                ?: source?.first?.takeIf { it.isNotBlank() }
                ?: manifest.optString("datasetId").takeIf { it.isNotBlank() && it != "null" }
                ?: UUID.randomUUID().toString()
            val generation = driveMetadata?.generation ?: source?.second ?: manifest.optLong("generation", 0L)
            val syncCompleted = driveMetadata != null
            db.execSQL("DELETE FROM sync_state")
            db.execSQL(
                """
                    INSERT INTO sync_state(
                        id, datasetId, deviceId, accountSubject, accountEmail,
                        localGeneration, lastSyncedGeneration, parentSnapshotId, lastSnapshotId,
                        conflictRemoteFileId, lastSyncedAt, status, lastError,
                        disabledDueToBilling, updatedAt
                    ) VALUES(1, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, NULL, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    datasetId,
                    targetDeviceId,
                    driveMetadata?.accountSubject ?: targetState?.accountSubject.takeIf { preserveTargetAccount },
                    driveMetadata?.accountEmail ?: targetState?.accountEmail.takeIf { preserveTargetAccount },
                    generation,
                    if (syncCompleted) generation else 0,
                    driveMetadata?.parentSnapshotId,
                    driveMetadata?.snapshotId,
                    if (syncCompleted) System.currentTimeMillis() else null,
                    if (syncCompleted) "SYNCED" else "DISCONNECTED",
                    if (preserveTargetAccount && targetState?.disabledDueToBilling == true) 1 else 0,
                    System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun collectAttachments(receipts: List<ReceiptEntity>): List<ExportAttachment> = receipts.mapNotNull { receipt ->
        val source = receipt.localPath?.let(::File)
        if (source == null || !source.exists() || !source.isFile) {
            return@mapNotNull null
        }
        require(isAppPrivate(source)) {
            "Lokasi lampiran ${receipt.storageId} tidak aman. Backup dibatalkan."
        }
        require(STORAGE_ID.matches(receipt.storageId)) { "Storage ID lampiran tidak valid" }
        val stats = if (receipt.encryptionVersion == EncryptedAttachmentStore.ENCRYPTION_VERSION) {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            attachmentStore.decrypt(source, object : OutputStream() {
                override fun write(value: Int) {
                    digest.update(value.toByte())
                    bytes++
                    require(bytes <= EncryptedAttachmentStore.MAX_ATTACHMENT_BYTES) { "Lampiran melebihi batas ukuran" }
                }

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    bytes += length
                    require(bytes <= EncryptedAttachmentStore.MAX_ATTACHMENT_BYTES) { "Lampiran melebihi batas ukuran" }
                    digest.update(buffer, offset, length)
                }
            })
            FileStats(bytes, digest.digest().toHex())
        } else {
            require(source.length() <= EncryptedAttachmentStore.MAX_ATTACHMENT_BYTES) { "Lampiran melebihi batas ukuran" }
            FileStats(source.length(), sha256(source))
        }
        ExportAttachment(
            receipt = receipt,
            file = source,
            entryName = "$ATTACHMENT_PREFIX${receipt.storageId}.bin",
            byteSize = stats.bytes,
            sha256 = stats.sha256,
        )
    }

    private fun decryptPackage(uri: Uri, password: CharArray, target: File) {
        val input = context.contentResolver.openInputStream(uri) ?: error("Tidak dapat membuka backup")
        try {
            input.buffered().use { raw ->
                val data = DataInputStream(raw)
                val magic = data.readExact(MAGIC_V2.size)
                when {
                    magic.contentEquals(MAGIC_V3) -> decryptV2(data, password, target)
                    magic.contentEquals(MAGIC_V2) -> decryptV2(data, password, target)
                    magic.contentEquals(MAGIC_V1) -> decryptV1(data, password, target)
                    else -> throw IllegalArgumentException("Format backup tidak dikenali")
                }
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (_: Exception) {
            throw IllegalArgumentException("Password salah atau backup rusak")
        }
    }

    private fun decryptV2(data: DataInputStream, password: CharArray, target: File) {
        require(password.size >= MIN_PASSWORD_LENGTH) { "Password backup v2 minimal 12 karakter" }
        val salt = data.readExact(SALT_BYTES)
        val nonce = data.readExact(NONCE_BYTES)
        val iterations = data.readInt()
        require(iterations in MIN_PBKDF2_ITERATIONS..MAX_PBKDF2_ITERATIONS) { "Parameter enkripsi backup tidak valid" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(GCM_TAG_BITS, nonce))
        target.outputStream().buffered().use { output ->
            CipherInputStream(LimitedInputStream(data, MAX_ENCRYPTED_PACKAGE_BYTES), cipher).use { decrypted ->
                decrypted.copyToWithLimit(output, MAX_PLAIN_PACKAGE_BYTES)
            }
        }
    }

    private fun decryptV1(data: DataInputStream, password: CharArray, target: File) {
        val salt = data.readExact(SALT_BYTES)
        val nonce = data.readExact(NONCE_BYTES)
        val encryptedSize = data.readInt()
        require(encryptedSize in 1..LEGACY_MAX_ENCRYPTED_BYTES) { "Ukuran backup tidak valid" }
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            deriveKey(password, salt, LEGACY_PBKDF2_ITERATIONS),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        target.outputStream().buffered().use { output ->
            CipherInputStream(ExactLengthInputStream(data, encryptedSize.toLong()), cipher).use { decrypted ->
                decrypted.copyToWithLimit(output, MAX_PLAIN_PACKAGE_BYTES)
            }
        }
        require(data.read() == -1) { "Backup memiliki data tambahan yang tidak valid" }
    }

    private fun extractPackage(packageFile: File, workspace: File): ExtractedPackage {
        var manifestBytes: ByteArray? = null
        var checksumBytes: ByteArray? = null
        var databaseFile: File? = null
        val attachments = linkedMapOf<String, ExtractedAttachment>()
        val seen = mutableSetOf<String>()
        var totalBytes = 0L
        ZipInputStream(packageFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                require(name.isNotBlank() && !name.contains('\\') && !name.startsWith('/') && !name.contains("../")) {
                    "Nama file dalam backup tidak aman"
                }
                require(seen.add(name)) { "Backup memiliki file ganda" }
                when {
                    name == MANIFEST_ENTRY -> {
                        manifestBytes = zip.readEntryWithLimit(MAX_MANIFEST_BYTES) { totalBytes += it }
                    }
                    name == CHECKSUM_ENTRY -> {
                        checksumBytes = zip.readEntryWithLimit(MAX_CHECKSUM_INDEX_BYTES) { totalBytes += it }
                    }
                    name == DATABASE_ENTRY -> {
                        val target = File(workspace, "database-extracted.sqlite")
                        target.outputStream().use { output ->
                            zip.copyEntryWithLimit(output, MAX_DATABASE_BYTES) { totalBytes += it }
                        }
                        databaseFile = target
                    }
                    ATTACHMENT_ENTRY.matches(name) -> {
                        require(attachments.size < MAX_ATTACHMENT_COUNT) { "Jumlah lampiran backup terlalu banyak" }
                        val storageId = ATTACHMENT_ENTRY.matchEntire(name)!!.groupValues[1]
                        val target = File(workspace, "attachment-$storageId.bin")
                        val digest = MessageDigest.getInstance("SHA-256")
                        var entryBytes = 0L
                        target.outputStream().use { fileOutput ->
                            val output = object : OutputStream() {
                                override fun write(value: Int) {
                                    fileOutput.write(value)
                                    digest.update(value.toByte())
                                    entryBytes++
                                }

                                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                                    fileOutput.write(buffer, offset, length)
                                    digest.update(buffer, offset, length)
                                    entryBytes += length
                                }
                            }
                            zip.copyEntryWithLimit(output, EncryptedAttachmentStore.MAX_ATTACHMENT_BYTES) { totalBytes += it }
                        }
                        attachments[name] = ExtractedAttachment(
                            entryName = name,
                            storageId = storageId,
                            file = target,
                            byteSize = entryBytes,
                            sha256 = digest.digest().toHex(),
                        )
                    }
                    else -> throw IllegalArgumentException("Backup memiliki file yang tidak dikenal: $name")
                }
                require(totalBytes <= MAX_EXTRACTED_BYTES) { "Isi backup melebihi batas aman" }
                zip.closeEntry()
            }
        }
        val manifest = JSONObject(requireNotNull(manifestBytes) { "Manifest backup tidak ditemukan" }.toString(Charsets.UTF_8))
        return ExtractedPackage(
            manifest = manifest,
            checksumIndex = checksumBytes?.toString(Charsets.UTF_8),
            database = requireNotNull(databaseFile) { "Database backup tidak ditemukan" },
            attachments = attachments,
        )
    }

    private fun verifyExtractedPackage(extracted: ExtractedPackage, format: Int) {
        val actualDatabaseSha = sha256(extracted.database)
        require(extracted.manifest.optString("databaseSha256") == actualDatabaseSha) {
            "Checksum database tidak cocok"
        }
        if (format == LEGACY_FORMAT) {
            require(extracted.attachments.isEmpty()) { "Backup lama tidak boleh memiliki lampiran terpisah" }
            return
        }
        require(extracted.manifest.optInt("attachmentCount", -1) == extracted.attachments.size) {
            "Jumlah lampiran backup tidak cocok"
        }
        val expected = parseChecksumIndex(requireNotNull(extracted.checksumIndex) { "Indeks checksum tidak ditemukan" })
        val actualNames = buildSet {
            add(DATABASE_ENTRY)
            addAll(extracted.attachments.keys)
        }
        require(expected.keys == actualNames) { "Indeks checksum backup tidak lengkap" }
        val databaseCheck = requireNotNull(expected[DATABASE_ENTRY])
        require(databaseCheck.sha256 == actualDatabaseSha && databaseCheck.bytes == extracted.database.length()) {
            "Metadata database tidak cocok"
        }
        extracted.attachments.forEach { (name, attachment) ->
            val check = requireNotNull(expected[name])
            require(check.sha256 == attachment.sha256 && check.bytes == attachment.byteSize) {
                "Checksum lampiran tidak cocok"
            }
        }
    }

    private fun parseChecksumIndex(value: String): Map<String, FileStats> {
        val result = linkedMapOf<String, FileStats>()
        value.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val parts = line.split('\t')
            require(parts.size == 3) { "Indeks checksum tidak valid" }
            val name = parts[0]
            require(name == DATABASE_ENTRY || ATTACHMENT_ENTRY.matches(name)) { "Nama checksum tidak valid" }
            val checksum = parts[1]
            require(SHA256.matches(checksum)) { "Checksum tidak valid" }
            val bytes = parts[2].toLongOrNull()
            require(bytes != null && bytes >= 0) { "Ukuran checksum tidak valid" }
            require(result.put(name, FileStats(bytes, checksum)) == null) { "Checksum ganda tidak diizinkan" }
        }
        return result
    }

    private fun migrateAndValidateCandidate(candidate: File) {
        val validationDatabase = KronDatabase.openPlaintextValidationDatabase(context, VALIDATION_DATABASE_NAME)
        try {
            validationDatabase.openHelper.writableDatabase.query("PRAGMA user_version").use { cursor ->
                require(cursor.moveToFirst() && cursor.getInt(0) == KronDatabase.SCHEMA_VERSION) {
                    "Migrasi database backup tidak selesai"
                }
            }
        } finally {
            validationDatabase.close()
        }
        validateDatabase(candidate)
    }

    private fun installReceiptPayloads(
        candidate: File,
        attachments: Map<String, ExtractedAttachment>,
        pendingReceipts: File,
    ) {
        val db = SQLiteDatabase.openDatabase(candidate.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        db.use { sqlite ->
            val receiptCount = scalar(sqlite, "SELECT COUNT(*) FROM receipts")
            require(receiptCount >= attachments.size.toLong()) {
                "Jumlah metadata lampiran melebihi jumlah receipt"
            }
            if (receiptCount > attachments.size.toLong()) {
                val placeholders = attachments.values.joinToString(",") { "?" }
                val params = attachments.values.map { it.storageId }.toTypedArray()
                sqlite.execSQL(
                    """
                        UPDATE receipts
                        SET localPath = NULL
                        WHERE localPath IS NOT NULL AND storageId NOT IN ($placeholders)
                    """.trimIndent(),
                    params,
                )
            }
            attachments.values.forEach { attachment ->
                val exists = sqlite.rawQuery(
                    "SELECT COUNT(*) FROM receipts WHERE storageId = ?",
                    arrayOf(attachment.storageId),
                ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == 1L }
                require(exists) { "Lampiran tidak memiliki metadata yang cocok" }
                val stagedFile = File(pendingReceipts, "${attachment.storageId}.kat")
                val stored = attachment.file.inputStream().use { input -> attachmentStore.encryptTo(input, stagedFile) }
                val finalPath = attachmentStore.destination(attachment.storageId).absolutePath
                sqlite.execSQL(
                    """
                        UPDATE receipts
                        SET localPath = ?, byteSize = ?, sha256 = ?, encryptionNonce = ?, encryptionVersion = ?
                        WHERE storageId = ?
                    """.trimIndent(),
                    arrayOf(
                        finalPath,
                        stored.byteSize,
                        stored.sha256,
                        stored.nonce,
                        stored.encryptionVersion,
                        attachment.storageId,
                    ),
                )
            }
        }
    }

    private fun installReceiptPayloadsDirect(
        candidate: File,
        attachments: Map<String, ExtractedAttachment>,
    ) {
        val db = SQLiteDatabase.openDatabase(candidate.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        db.use { sqlite ->
            val receiptCount = scalar(sqlite, "SELECT COUNT(*) FROM receipts")
            require(receiptCount >= attachments.size.toLong()) {
                "Jumlah metadata lampiran melebihi jumlah receipt"
            }
            if (receiptCount > attachments.size.toLong()) {
                val placeholders = attachments.values.joinToString(",") { "?" }
                val params = attachments.values.map { it.storageId }.toTypedArray()
                sqlite.execSQL(
                    """
                        UPDATE receipts
                        SET localPath = NULL
                        WHERE localPath IS NOT NULL AND storageId NOT IN ($placeholders)
                    """.trimIndent(),
                    params,
                )
            }
            attachments.values.forEach { attachment ->
                val exists = sqlite.rawQuery(
                    "SELECT COUNT(*) FROM receipts WHERE storageId = ?",
                    arrayOf(attachment.storageId),
                ).use { cursor -> cursor.moveToFirst() && cursor.getLong(0) == 1L }
                require(exists) { "Lampiran tidak memiliki metadata yang cocok" }
                val finalFile = attachmentStore.destination(attachment.storageId)
                finalFile.parentFile?.mkdirs()
                val stored = attachment.file.inputStream().use { input -> attachmentStore.encryptTo(input, finalFile) }
                sqlite.execSQL(
                    """
                        UPDATE receipts
                        SET localPath = ?, byteSize = ?, sha256 = ?, encryptionNonce = ?, encryptionVersion = ?
                        WHERE storageId = ?
                    """.trimIndent(),
                    arrayOf(
                        finalFile.absolutePath,
                        stored.byteSize,
                        stored.sha256,
                        stored.nonce,
                        stored.encryptionVersion,
                        attachment.storageId,
                    ),
                )
            }
        }
    }

    private fun validateDatabase(file: File) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            require(scalar(it, "PRAGMA user_version") == KronDatabase.SCHEMA_VERSION.toLong()) {
                "Versi database hasil migrasi tidak sesuai"
            }
            val integrity = it.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }
            require(integrity) { "Integritas database tidak valid" }
            require(!it.rawQuery("PRAGMA foreign_key_check", null).use { cursor -> cursor.moveToFirst() }) {
                "Relasi database tidak valid"
            }
            val cash = scalar(it, "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines")
            val available = scalar(
                it,
                "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines WHERE bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL",
            )
            require(cash == available) { "Invariant total aset backup tidak seimbang" }
            listOf(FundingChannel.CASH, FundingChannel.EBUDGET).forEach { channel ->
                val channelCash = scalar(
                    it,
                    "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines WHERE fundingChannel='$channel'",
                )
                val channelAvailable = scalar(
                    it,
                    "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines WHERE fundingChannel='$channel' AND (bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)",
                )
                require(channelCash == channelAvailable) { "Invariant kanal $channel tidak seimbang" }
            }
            val accounts = it.rawQuery("SELECT id FROM accounts", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(0))
                }
            }
            accounts.forEach { accountId ->
                val accountCash = scalar(
                    it,
                    "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines WHERE accountId=$accountId",
                )
                val accountAvailable = scalar(
                    it,
                    "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines WHERE accountId=$accountId " +
                        "AND (bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)",
                )
                require(accountCash == accountAvailable) { "Invariant akun tidak seimbang" }
                listOf(FundingChannel.CASH, FundingChannel.EBUDGET).forEach { channel ->
                    val channelCash = scalar(
                        it,
                        "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines " +
                            "WHERE accountId=$accountId AND fundingChannel='$channel'",
                    )
                    val channelAvailable = scalar(
                        it,
                        "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines " +
                            "WHERE accountId=$accountId AND fundingChannel='$channel' " +
                            "AND (bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)",
                    )
                    require(channelCash == channelAvailable) { "Invariant kanal akun tidak seimbang" }
                }
            }
        }
    }

    private fun scalar(database: SQLiteDatabase, sql: String): Long = database.rawQuery(sql, null).use { cursor ->
        require(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun isAppPrivate(file: File): Boolean {
        val path = file.canonicalFile.toPath()
        return path.startsWith(context.filesDir.canonicalFile.toPath()) ||
            path.startsWith(context.noBackupFilesDir.canonicalFile.toPath())
    }

    private fun ZipOutputStream.writeEntry(name: String, value: ByteArray) {
        putNextEntry(stableZipEntry(name))
        write(value)
        closeEntry()
    }

    private fun stableZipEntry(name: String): ZipEntry = ZipEntry(name).apply { time = 0L }

    private fun ZipInputStream.readEntryWithLimit(limit: Long, onBytes: (Long) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        copyEntryWithLimit(output, limit, onBytes)
        return output.toByteArray()
    }

    private fun InputStream.copyEntryWithLimit(output: OutputStream, limit: Long, onBytes: (Long) -> Unit) {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            total += read
            require(total <= limit) { "File dalam backup melebihi batas aman" }
            onBytes(read.toLong())
            output.write(buffer, 0, read)
        }
    }

    private fun InputStream.copyToWithLimit(output: OutputStream, limit: Long) {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            total += read
            require(total <= limit) { "Data melebihi batas aman" }
            output.write(buffer, 0, read)
        }
    }

    private fun File.copyToAndSync(target: File) {
        inputStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun writeTextAndSync(target: File, value: String) {
        target.parentFile?.mkdirs()
        FileOutputStream(target, false).use { output ->
            output.write(value.toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
        syncDirectoryStatic(requireNotNull(target.parentFile))
    }

    private fun deleteScopedDirectory(directory: File, parent: File) {
        if (!directory.exists()) return
        val canonical = directory.canonicalFile
        require(canonical.toPath().startsWith(parent.canonicalFile.toPath()) && canonical != parent.canonicalFile) {
            "Lokasi kerja restore tidak aman"
        }
        canonical.deleteRecursively()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun DataInputStream.readExact(size: Int): ByteArray = ByteArray(size).also(::readFully)

    private class LimitedInputStream(
        private val source: InputStream,
        private val limit: Long,
    ) : InputStream() {
        private var count = 0L

        override fun read(): Int {
            val value = source.read()
            if (value >= 0) account(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = source.read(buffer, offset, length)
            if (read > 0) account(read.toLong())
            return read
        }

        private fun account(bytes: Long) {
            count += bytes
            require(count <= limit) { "Backup terenkripsi melebihi batas aman" }
        }
    }

    private class ExactLengthInputStream(
        private val source: InputStream,
        private var remaining: Long,
    ) : InputStream() {
        override fun read(): Int {
            if (remaining == 0L) return -1
            val value = source.read()
            if (value < 0) throw IllegalArgumentException("Backup terpotong")
            remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val read = source.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            if (read < 0) throw IllegalArgumentException("Backup terpotong")
            remaining -= read
            return read
        }
    }

    private data class ExportAttachment(
        val receipt: ReceiptEntity,
        val file: File,
        val entryName: String,
        val byteSize: Long,
        val sha256: String,
    )

    private data class ExtractedAttachment(
        val entryName: String,
        val storageId: String,
        val file: File,
        val byteSize: Long,
        val sha256: String,
    )

    private data class ExtractedPackage(
        val manifest: JSONObject,
        val checksumIndex: String?,
        val database: File,
        val attachments: Map<String, ExtractedAttachment>,
    )

    private data class DriveRestoreMetadata(
        val datasetId: String,
        val generation: Long,
        val parentSnapshotId: String?,
        val snapshotId: String,
        val accountSubject: String,
        val accountEmail: String,
    )

    private data class FileStats(val bytes: Long, val sha256: String)

    private fun recreateAppendOnlyTriggers(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val immutableTables = listOf(
            "activity_events", "cash_journal_lines", "budget_journal_lines",
            "transaction_splits", "audit_snapshots", "ledger_lines",
            "journal_seals", "evidence_keys", "team_invitation_uses", "team_event_proofs",
        )
        immutableTables.forEach { table ->
            listOf("update", "delete").forEach { operation ->
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS append_only_${table}_$operation
                    BEFORE $operation ON $table
                    BEGIN
                        SELECT RAISE(ABORT, 'Catatan audit KRON bersifat append-only');
                    END
                """.trimIndent())
            }
        }
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS append_only_receipts_update
            BEFORE UPDATE ON receipts
            WHEN NEW.eventId != OLD.eventId OR NEW.storageId != OLD.storageId
               OR NEW.displayName != OLD.displayName OR NEW.mimeType != OLD.mimeType
               OR NEW.byteSize != OLD.byteSize OR NEW.sha256 != OLD.sha256
               OR NEW.createdAt != OLD.createdAt
               OR COALESCE(NEW.capturedAt, -1) != COALESCE(OLD.capturedAt, -1)
               OR COALESCE(NEW.latitude, 999) != COALESCE(OLD.latitude, 999)
               OR COALESCE(NEW.longitude, 999) != COALESCE(OLD.longitude, 999)
               OR NEW.origin != OLD.origin
               OR COALESCE(NEW.evidenceEventId, '') != COALESCE(OLD.evidenceEventId, '')
            BEGIN
                SELECT RAISE(ABORT, 'Metadata bukti KRON bersifat append-only');
            END
        """.trimIndent())
        db.execSQL("""
            CREATE TRIGGER IF NOT EXISTS append_only_receipts_delete
            BEFORE DELETE ON receipts
            BEGIN
                SELECT RAISE(ABORT, 'Bukti KRON bersifat append-only');
            END
        """.trimIndent())
    }

    private fun recreateSyncWriteGuardTriggers(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val guardedTables = listOf(
            "accounts", "categories", "portfolios",
            "budget_periods", "allocations",
            "portfolio_allocation_templates", "activity_events",
            "recurring_rules", "receipts",
            "cash_journal_lines", "budget_journal_lines",
            "transaction_splits", "recurring_occurrences",
            "audit_snapshots", "ledger_accounts",
            "ledger_lines", "journal_seals", "evidence_keys",
            "team_workspaces", "team_members", "team_invitation_uses", "team_event_proofs",
        )
        guardedTables.forEach { table ->
            for (op in listOf("INSERT", "UPDATE", "DELETE")) {
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS sync_write_guard_${table}_${op.lowercase()}
                    BEFORE $op ON $table
                    WHEN EXISTS(
                        SELECT 1 FROM sync_state
                        WHERE id = 1 AND status IN ('SYNCING','RESTART_REQUIRED')
                    )
                    BEGIN
                        SELECT RAISE(ABORT, 'KRON sedang menyinkronkan atau menunggu restart');
                    END
                """.trimIndent())
            }
        }
    }

    private fun readConflictDataset(
        snapshotId: String?,
        query: (String) -> Cursor,
    ): ConflictDataset {
        val events = query(
            """
            SELECT e.id,e.type,e.title,e.note,e.source,e.effectiveEpochDay,e.createdAt,
                   COALESCE((SELECT name FROM accounts WHERE id=e.accountId),'Akun'),
                   EXISTS(SELECT 1 FROM activity_events r WHERE r.type='REVERSAL' AND r.relatedEventId=e.id),
                   (SELECT COUNT(*) FROM receipts x WHERE x.eventId=e.id OR x.evidenceEventId=e.id),
                   COALESCE(s.actor,''),COALESCE(s.deviceId,''),COALESCE(s.recordedAtUtc,e.createdAt),
                   COALESCE(s.payloadHash,''),COALESCE(s.signatureBase64,''),
                   COALESCE((SELECT SUM(amount) FROM cash_journal_lines c WHERE c.eventId=e.id AND c.accountId=e.accountId),0),
                   COALESCE((SELECT SUM(amount) FROM budget_journal_lines b WHERE b.eventId=e.id AND b.accountId=e.accountId),0)
            FROM activity_events e
            LEFT JOIN journal_seals s ON s.eventId=e.id
            ORDER BY e.effectiveEpochDay,e.createdAt,e.id
            """.trimIndent(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val fallback = buildString {
                        for (index in 0..12) append(cursor.getString(index)).append('\u001f')
                        append(cursor.getLong(15)).append('\u001f').append(cursor.getLong(16))
                    }
                    val payloadHash = cursor.getString(13).takeIf(String::isNotBlank) ?: sha256Text(fallback)
                    val signature = cursor.getString(14)
                    add(
                        ConflictEventRecord(
                            eventId = cursor.getString(0),
                            type = cursor.getString(1),
                            title = cursor.getString(2),
                            amount = cursor.getLong(15),
                            effectiveEpochDay = cursor.getLong(5),
                            accountName = cursor.getString(7),
                            reversed = cursor.getInt(8) != 0,
                            receiptCount = cursor.getInt(9),
                            actor = cursor.getString(10),
                            deviceId = cursor.getString(11),
                            changedAtEpochMillis = cursor.getLong(12),
                            canonicalHash = payloadHash,
                            signatureHash = signature.takeIf(String::isNotBlank)?.let(::sha256Text) ?: "missing",
                        ),
                    )
                }
            }
        }
        val mutableQueries = listOf(
            "SELECT 'account',COALESCE(teamId,'private-account:'||id),name,revision,name||'|'||isArchived||'|'||sharingMode FROM accounts",
            "SELECT 'category',syncId,name,revision,name||'|'||direction||'|'||color||'|'||icon||'|'||isArchived FROM categories WHERE syncId IS NOT NULL",
            "SELECT 'portfolio',syncId,name,revision,name||'|'||cadence||'|'||intervalCount||'|'||plannedIncome||'|'||rolloverEnabled||'|'||fundingPriority||'|'||startEpochDay||'|'||endMode||'|'||COALESCE(endValue,'')||'|'||isPaused||'|'||isArchived FROM portfolios WHERE syncId IS NOT NULL",
            "SELECT 'period',p.syncId,pf.name||' '||p.startEpochDay,p.revision,pf.syncId||'|'||p.startEpochDay||'|'||p.endEpochDay||'|'||p.status FROM budget_periods p JOIN portfolios pf ON pf.id=p.portfolioId WHERE p.syncId IS NOT NULL",
            "SELECT 'allocation',a.syncId,c.name||' '||a.fundingChannel,a.revision,p.syncId||'|'||c.syncId||'|'||a.fundingChannel||'|'||a.plannedAmount FROM allocations a JOIN budget_periods p ON p.id=a.periodId JOIN categories c ON c.id=a.categoryId WHERE a.syncId IS NOT NULL",
            "SELECT 'template',t.syncId,c.name,t.revision,p.syncId||'|'||c.syncId||'|'||t.plannedAmount||'|'||t.cashPercentage FROM portfolio_allocation_templates t JOIN portfolios p ON p.id=t.portfolioId JOIN categories c ON c.id=t.categoryId WHERE t.syncId IS NOT NULL",
            "SELECT 'rule',r.syncId,r.title,r.revision,r.title||'|'||r.direction||'|'||r.amount||'|'||r.fundingChannel||'|'||COALESCE(c.syncId,'')||'|'||COALESCE(a.syncId,'')||'|'||r.cadence||'|'||r.intervalCount||'|'||r.anchorMonth||'|'||r.anchorDay||'|'||r.startEpochDay||'|'||r.nextEpochDay||'|'||COALESCE(r.endEpochDay,'')||'|'||COALESCE(r.remainingOccurrences,'')||'|'||r.isPaused FROM recurring_rules r LEFT JOIN categories c ON c.id=r.categoryId LEFT JOIN allocations a ON a.id=r.allocationId WHERE r.syncId IS NOT NULL",
        )
        val mutable = buildList {
            mutableQueries.forEach { sql ->
                query(sql).use { cursor ->
                    while (cursor.moveToNext()) {
                        add(
                            ConflictMutableRecord(
                                entityType = cursor.getString(0),
                                syncId = cursor.getString(1),
                                label = cursor.getString(2),
                                revision = cursor.getLong(3),
                                canonicalHash = sha256Text(cursor.getString(4)),
                            ),
                        )
                    }
                }
            }
        }
        return ConflictDataset(snapshotId, events, mutable)
    }

    private fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val CURRENT_FORMAT = 2
        private const val LEGACY_FORMAT = 1
        private const val MIN_PASSWORD_LENGTH = 12
        private const val LEGACY_MIN_PASSWORD_LENGTH = 8
        private const val PBKDF2_ITERATIONS = 600_000
        private const val LEGACY_PBKDF2_ITERATIONS = 210_000
        private const val MIN_PBKDF2_ITERATIONS = 210_000
        private const val MAX_PBKDF2_ITERATIONS = 2_000_000
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val DATABASE_ENTRY = "database.sqlite"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val CHECKSUM_ENTRY = "checksums.tsv"
        private const val ATTACHMENT_PREFIX = "attachments/"
        private const val READY_FILE = "ready.json"
        private const val TRANSACTION_FILE = "swap.json"
        private const val PREPARED_MARKER = "prepared"
        private const val LIVE_MOVED_MARKER = "live_moved"
        private const val NEW_INSTALLED_MARKER = "new_installed"
        private const val COMMITTED_MARKER = "committed"
        private const val RECEIPTS_DIRECTORY = "receipts"
        private const val PENDING_DIRECTORY = "pending-restore-v2"
        private const val VALIDATION_DATABASE_NAME = "kron-restore-validation.db"
        private const val MAX_MANIFEST_BYTES = 64L * 1024
        private const val MAX_CHECKSUM_INDEX_BYTES = 1024L * 1024
        private const val MAX_DATABASE_BYTES = 250L * 1024 * 1024
        private const val MAX_EXTRACTED_BYTES = 500L * 1024 * 1024
        private const val MAX_PLAIN_PACKAGE_BYTES = 500L * 1024 * 1024
        private const val MAX_ENCRYPTED_PACKAGE_BYTES = 550L * 1024 * 1024
        private const val MAX_SYNC_PAYLOAD_BYTES = 100L * 1024 * 1024
        private const val LEGACY_MAX_ENCRYPTED_BYTES = 250 * 1024 * 1024
        private const val MAX_ATTACHMENT_COUNT = 500
        private val MAGIC_V1 = "KRONBKP1".toByteArray(Charsets.US_ASCII)
        private val MAGIC_V2 = "KRONBKP2".toByteArray(Charsets.US_ASCII)
        private val MAGIC_V3 = "KRONBKP3".toByteArray(Charsets.US_ASCII)
        private val STORAGE_ID = Regex("[A-Za-z0-9_-]{8,128}")
        private val ATTACHMENT_ENTRY = Regex("attachments/([A-Za-z0-9_-]{8,128})\\.bin")
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun applyPendingRestore(context: Context) {
            SqlCipherLibrary.ensureLoaded()
            val pending = File(context.filesDir, PENDING_DIRECTORY)
            if (!pending.exists()) return
            val paths = restoreSwapPaths(context, pending)

            if (paths.committedMarker.exists()) {
                finalizeCommittedRestore(paths)
                return
            }

            if (paths.transactionFile.exists() || paths.hasIncompleteMarker()) {
                val metadata = readSwapMetadata(paths.transactionFile)
                rollbackIncompleteRestore(paths, metadata)
                return
            }

            val ready = runCatching { validatePendingRestore(paths) }.getOrElse {
                deleteChildRecursively(pending, context.filesDir)
                return
            }
            val metadata = captureSwapMetadata(paths)
            listOf(paths.oldDatabase, paths.oldWal, paths.oldShm, paths.oldReceipts).forEach { it.delete() }

            runCatching {
                deletePath(paths.newDatabase)
                deletePath(paths.newReceipts)
                writeTransactionMetadata(paths.transactionFile, metadata.toJson().toString())

                copyFileToNewAndSync(paths.pendingDatabase, paths.newDatabase)
                copyReceiptDirectoryToNew(paths.pendingReceipts, paths.newReceipts, ready.attachmentCount)
                require(sha256Static(paths.newDatabase) == ready.databaseSha256) {
                    "Checksum database staging restore tidak cocok"
                }
                validateReceiptDirectory(paths.newReceipts, ready.attachmentCount)
                require(directorySha256Static(paths.newReceipts) == ready.receiptsSha256) {
                    "Checksum lampiran staging restore tidak cocok"
                }
                writeMarker(paths.preparedMarker)

                moveIfPresent(paths.liveDatabase, paths.oldDatabase)
                moveIfPresent(paths.liveWal, paths.oldWal)
                moveIfPresent(paths.liveShm, paths.oldShm)
                moveIfPresent(paths.liveReceipts, paths.oldReceipts)
                writeMarker(paths.liveMovedMarker)

                moveSameParent(paths.newDatabase, paths.liveDatabase)
                moveSameParent(paths.newReceipts, paths.liveReceipts)
                writeMarker(paths.newInstalledMarker)

                require(sha256Static(paths.liveDatabase) == ready.databaseSha256) {
                    "Checksum database terpasang tidak cocok"
                }
                validateReceiptDirectory(paths.liveReceipts, ready.attachmentCount)
                require(directorySha256Static(paths.liveReceipts) == ready.receiptsSha256) {
                    "Checksum lampiran terpasang tidak cocok"
                }
                writeMarker(paths.committedMarker)
            }.onFailure { restoreError ->
                runCatching { rollbackIncompleteRestore(paths, metadata) }
                    .onFailure { rollbackError ->
                        restoreError.addSuppressed(rollbackError)
                        throw IllegalStateException("Restore gagal dan data lama tidak dapat dipulihkan", restoreError)
                    }
            }
        }

        private val RESTORED_ATTACHMENT = Regex("[A-Za-z0-9_-]{8,128}\\.kat")

        private fun deleteDatabaseFiles(database: File) {
            database.delete()
            File(database.path + "-wal").delete()
            File(database.path + "-shm").delete()
        }

        private fun sha256Static(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun directorySha256Static(directory: File): String {
            require(directory.isDirectory) { "Direktori checksum tidak valid" }
            val entries = directory.listFiles() ?: error("Direktori checksum tidak dapat dibaca")
            require(entries.all(File::isFile)) { "Direktori lampiran memiliki isi yang tidak valid" }
            val digest = MessageDigest.getInstance("SHA-256")
            entries.sortedBy(File::getName).forEach { entry ->
                digest.update(entry.name.toByteArray(Charsets.UTF_8))
                digest.update(0)
                entry.inputStream().buffered().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun validatePendingRestore(paths: RestoreSwapPaths): RestoreReady {
            require(paths.readyFile.isFile && paths.pendingDatabase.isFile && paths.pendingReceipts.isDirectory) {
                "Restore staging tidak lengkap"
            }
            val ready = JSONObject(paths.readyFile.readText(Charsets.UTF_8))
            require(ready.optInt("format", -1) == CURRENT_FORMAT) { "Versi staging restore tidak didukung" }
            val checksum = ready.optString("databaseSha256")
            require(SHA256.matches(checksum) && checksum == sha256Static(paths.pendingDatabase)) {
                "Checksum database restore tidak cocok"
            }
            val attachmentCount = ready.optInt("attachmentCount", -1)
            require(attachmentCount in 0..MAX_ATTACHMENT_COUNT) { "Jumlah lampiran restore tidak valid" }
            validateReceiptDirectory(paths.pendingReceipts, attachmentCount)
            val receiptsChecksum = ready.optString("receiptsSha256")
            require(SHA256.matches(receiptsChecksum) && receiptsChecksum == directorySha256Static(paths.pendingReceipts)) {
                "Checksum lampiran restore tidak cocok"
            }
            return RestoreReady(checksum, attachmentCount, receiptsChecksum)
        }

        private fun captureSwapMetadata(paths: RestoreSwapPaths): RestoreSwapMetadata = RestoreSwapMetadata(
            hadDatabase = paths.liveDatabase.isFile,
            hadWal = paths.liveWal.isFile,
            hadShm = paths.liveShm.isFile,
            hadReceipts = paths.liveReceipts.isDirectory,
            databaseSha256 = paths.liveDatabase.takeIf(File::isFile)?.let(::sha256Static),
            receiptsSha256 = paths.liveReceipts.takeIf(File::isDirectory)?.let(::directorySha256Static),
        )

        private fun rollbackIncompleteRestore(paths: RestoreSwapPaths, metadata: RestoreSwapMetadata) {
            restoreFile(paths.oldDatabase, paths.liveDatabase, metadata.hadDatabase)
            restoreFile(paths.oldWal, paths.liveWal, metadata.hadWal)
            restoreFile(paths.oldShm, paths.liveShm, metadata.hadShm)
            restoreDirectory(paths.oldReceipts, paths.liveReceipts, metadata.hadReceipts)

            deletePath(paths.newDatabase)
            deletePath(paths.newReceipts)
            deletePath(paths.oldDatabase)
            deletePath(paths.oldWal)
            deletePath(paths.oldShm)
            deletePath(paths.oldReceipts)

            if (metadata.hadDatabase) {
                require(paths.liveDatabase.isFile) { "Database lama tidak dapat dipulihkan" }
                metadata.databaseSha256?.let { expected ->
                    require(sha256Static(paths.liveDatabase) == expected) {
                        "Checksum database lama berubah saat rollback"
                    }
                }
            } else {
                require(!paths.liveDatabase.exists()) { "Database baru gagal dibatalkan" }
            }
            if (metadata.hadReceipts) {
                require(paths.liveReceipts.isDirectory) { "Direktori lampiran lama tidak dapat dipulihkan" }
                metadata.receiptsSha256?.let { expected ->
                    require(directorySha256Static(paths.liveReceipts) == expected) {
                        "Checksum direktori lampiran berubah saat rollback"
                    }
                }
            } else {
                require(!paths.liveReceipts.exists()) { "Direktori lampiran baru gagal dibatalkan" }
            }
            deleteChildRecursively(paths.pending, paths.pending.parentFile ?: error("Lokasi restore tidak valid"))
        }

        private fun finalizeCommittedRestore(paths: RestoreSwapPaths) {
            deletePath(paths.oldDatabase)
            deletePath(paths.oldWal)
            deletePath(paths.oldShm)
            deletePath(paths.oldReceipts)
            deletePath(paths.newDatabase)
            deletePath(paths.newReceipts)
            deleteChildRecursively(paths.pending, paths.pending.parentFile ?: error("Lokasi restore tidak valid"))
        }

        private fun restoreFile(old: File, live: File, existedBefore: Boolean) {
            if (old.exists()) {
                deletePath(live)
                moveSameParent(old, live)
            } else if (!existedBefore) {
                deletePath(live)
            }
        }

        private fun restoreDirectory(old: File, live: File, existedBefore: Boolean) {
            if (old.exists()) {
                deletePath(live)
                moveSameParent(old, live)
            } else if (!existedBefore) {
                deletePath(live)
            }
        }

        private fun moveIfPresent(source: File, target: File) {
            if (source.exists()) moveSameParent(source, target)
        }

        private fun moveSameParent(source: File, target: File) {
            require(source.exists()) { "Sumber swap restore tidak ditemukan" }
            require(!target.exists()) { "Tujuan swap restore sudah ada" }
            require(source.canonicalFile.parentFile == target.canonicalFile.parentFile) {
                "Swap restore harus berada pada filesystem yang sama"
            }
            try {
                Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source.toPath(), target.toPath())
            }
            syncDirectoryStatic(requireNotNull(target.parentFile))
        }

        private fun copyFileToNewAndSync(source: File, target: File) {
            require(source.isFile && !target.exists()) { "File staging restore tidak valid" }
            target.parentFile?.mkdirs()
            source.inputStream().use { input ->
                FileOutputStream(target, false).use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            syncDirectoryStatic(requireNotNull(target.parentFile))
        }

        private fun copyReceiptDirectoryToNew(source: File, target: File, expectedCount: Int) {
            validateReceiptDirectory(source, expectedCount)
            require(!target.exists() && target.mkdir()) { "Direktori staging lampiran tidak dapat dibuat" }
            syncDirectoryStatic(requireNotNull(target.parentFile))
            syncDirectoryStatic(target)
            source.listFiles().orEmpty().sortedBy(File::getName).forEach { receipt ->
                copyFileToNewAndSync(receipt, File(target, receipt.name))
            }
            validateReceiptDirectory(target, expectedCount)
        }

        private fun validateReceiptDirectory(directory: File, expectedCount: Int) {
            require(directory.isDirectory) { "Direktori lampiran restore tidak ditemukan" }
            val entries = directory.listFiles() ?: error("Direktori lampiran restore tidak dapat dibaca")
            require(entries.size == expectedCount) { "Jumlah lampiran restore tidak cocok" }
            require(entries.all { it.isFile && RESTORED_ATTACHMENT.matches(it.name) }) {
                "Direktori lampiran restore memiliki isi yang tidak valid"
            }
            require(entries.map(File::getName).toSet().size == entries.size) {
                "Lampiran restore ganda tidak diizinkan"
            }
        }

        private fun writeMarker(marker: File) {
            writeTextAndSyncStatic(marker, Instant.now().toString())
        }

        private fun writeTransactionMetadata(target: File, value: String) {
            val preparing = File(target.parentFile, "${target.name}.preparing")
            deletePath(preparing)
            writeTextAndSyncStatic(preparing, value)
            moveSameParent(preparing, target)
        }

        private fun writeTextAndSyncStatic(target: File, value: String) {
            target.parentFile?.mkdirs()
            FileOutputStream(target, false).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            syncDirectoryStatic(requireNotNull(target.parentFile))
        }

        private fun readSwapMetadata(file: File): RestoreSwapMetadata {
            require(file.isFile) { "Metadata swap restore tidak ditemukan" }
            return RestoreSwapMetadata.fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        }

        private fun deletePath(path: File) {
            if (!path.exists()) return
            val parent = path.parentFile
            if (path.isDirectory) {
                require(path.deleteRecursively()) { "Direktori restore tidak dapat dibersihkan" }
            } else {
                require(path.delete()) { "File restore tidak dapat dibersihkan" }
            }
            parent?.let(::syncDirectoryStatic)
        }

        private fun deleteChildRecursively(child: File, parent: File) {
            if (!child.exists()) return
            val canonicalChild = child.canonicalFile
            val canonicalParent = parent.canonicalFile
            require(canonicalChild != canonicalParent && canonicalChild.toPath().startsWith(canonicalParent.toPath())) {
                "Lokasi pembersihan restore tidak aman"
            }
            require(canonicalChild.deleteRecursively()) { "Data staging restore tidak dapat dibersihkan" }
            syncDirectoryStatic(canonicalParent)
        }

        private fun syncDirectoryStatic(directory: File) {
            val descriptor = Os.open(
                directory.absolutePath,
                OsConstants.O_RDONLY,
                0,
            )
            try {
                Os.fsync(descriptor)
            } finally {
                Os.close(descriptor)
            }
        }

        private fun restoreSwapPaths(context: Context, pending: File): RestoreSwapPaths {
            val liveDatabase = context.getDatabasePath(KronDatabase.DATABASE_NAME)
            val databaseParent = requireNotNull(liveDatabase.parentFile).apply { mkdirs() }
            val receiptsParent = context.noBackupFilesDir.apply { mkdirs() }
            return RestoreSwapPaths(
                pending = pending,
                readyFile = File(pending, READY_FILE),
                transactionFile = File(pending, TRANSACTION_FILE),
                pendingDatabase = File(pending, DATABASE_ENTRY),
                pendingReceipts = File(pending, RECEIPTS_DIRECTORY),
                preparedMarker = File(pending, PREPARED_MARKER),
                liveMovedMarker = File(pending, LIVE_MOVED_MARKER),
                newInstalledMarker = File(pending, NEW_INSTALLED_MARKER),
                committedMarker = File(pending, COMMITTED_MARKER),
                liveDatabase = liveDatabase,
                liveWal = File(liveDatabase.path + "-wal"),
                liveShm = File(liveDatabase.path + "-shm"),
                newDatabase = File(databaseParent, ".${liveDatabase.name}.restore-new-v2"),
                oldDatabase = File(databaseParent, ".${liveDatabase.name}.restore-old-v2"),
                oldWal = File(databaseParent, ".${liveDatabase.name}-wal.restore-old-v2"),
                oldShm = File(databaseParent, ".${liveDatabase.name}-shm.restore-old-v2"),
                liveReceipts = File(receiptsParent, RECEIPTS_DIRECTORY),
                newReceipts = File(receiptsParent, ".$RECEIPTS_DIRECTORY.restore-new-v2"),
                oldReceipts = File(receiptsParent, ".$RECEIPTS_DIRECTORY.restore-old-v2"),
            )
        }

        private data class RestoreReady(
            val databaseSha256: String,
            val attachmentCount: Int,
            val receiptsSha256: String,
        )

        private data class RestoreSwapMetadata(
            val hadDatabase: Boolean,
            val hadWal: Boolean,
            val hadShm: Boolean,
            val hadReceipts: Boolean,
            val databaseSha256: String?,
            val receiptsSha256: String?,
        ) {
            fun toJson(): JSONObject = JSONObject()
                .put("hadDatabase", hadDatabase)
                .put("hadWal", hadWal)
                .put("hadShm", hadShm)
                .put("hadReceipts", hadReceipts)
                .put("databaseSha256", databaseSha256 ?: JSONObject.NULL)
                .put("receiptsSha256", receiptsSha256 ?: JSONObject.NULL)

            companion object {
                fun fromJson(json: JSONObject): RestoreSwapMetadata {
                    val checksum = json.optString("databaseSha256").takeIf { it.isNotBlank() && it != "null" }
                    val receiptsChecksum = json.optString("receiptsSha256").takeIf { it.isNotBlank() && it != "null" }
                    require(checksum == null || SHA256.matches(checksum)) { "Checksum metadata rollback tidak valid" }
                    require(receiptsChecksum == null || SHA256.matches(receiptsChecksum)) {
                        "Checksum metadata lampiran rollback tidak valid"
                    }
                    return RestoreSwapMetadata(
                        hadDatabase = json.getBoolean("hadDatabase"),
                        hadWal = json.getBoolean("hadWal"),
                        hadShm = json.getBoolean("hadShm"),
                        hadReceipts = json.getBoolean("hadReceipts"),
                        databaseSha256 = checksum,
                        receiptsSha256 = receiptsChecksum,
                    )
                }
            }
        }

        private data class RestoreSwapPaths(
            val pending: File,
            val readyFile: File,
            val transactionFile: File,
            val pendingDatabase: File,
            val pendingReceipts: File,
            val preparedMarker: File,
            val liveMovedMarker: File,
            val newInstalledMarker: File,
            val committedMarker: File,
            val liveDatabase: File,
            val liveWal: File,
            val liveShm: File,
            val newDatabase: File,
            val oldDatabase: File,
            val oldWal: File,
            val oldShm: File,
            val liveReceipts: File,
            val newReceipts: File,
            val oldReceipts: File,
        ) {
            fun hasIncompleteMarker(): Boolean = preparedMarker.exists() || liveMovedMarker.exists() || newInstalledMarker.exists()
        }
    }
}
