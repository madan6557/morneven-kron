package com.morneven.kron.team

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.morneven.kron.data.AccountSharingMode
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.TeamRole
import com.morneven.kron.data.TeamWorkspaceStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ConversionResult(
    val accountIds: List<Long>,
    val preConversionBackupPath: String,
)

data class ConversionRequest(
    val teamId: String,
    val folderId: String,
    val localRole: String,
    val ownerSubjectHash: String,
    val ownerEmail: String,
    val ownerDisplayName: String? = null,
    val headSnapshotId: String? = null,
)

@Singleton
class TeamConversionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: KronDatabase,
) {
    suspend fun convertPrivateToTeam(
        accountIds: List<Long>,
        request: ConversionRequest,
    ): ConversionResult {
        require(accountIds.isNotEmpty()) { "Tidak ada akun yang akan dikonversi" }
        require(request.teamId.isNotBlank() && request.folderId.isNotBlank()) { "Informasi Team tidak lengkap" }
        require(listOf(TeamRole.OWNER, TeamRole.EDITOR, TeamRole.VIEWER).any { it == request.localRole }) {
            "Role ${request.localRole} tidak valid"
        }
        validatePrivateAccounts(accountIds)
        val now = System.currentTimeMillis()
        val backupPath = createPreConversionBackup()
        val stagingDb = File(context.cacheDir, "team-convert-${UUID.randomUUID()}.db")
        try {
            copyLiveToStaging(stagingDb)
            SQLiteDatabase.openDatabase(stagingDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("PRAGMA foreign_keys=ON")
                db.beginTransaction()
                try {
                    for (accountId in accountIds) {
                        val a = accountId.toString()
                        db.execSQL(
                            "UPDATE accounts SET sharingMode=?, teamId=?, updatedAt=? WHERE id=?",
                            arrayOf<Any?>(AccountSharingMode.TEAM, request.teamId, now, a),
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO team_workspaces VALUES(?,?,?,?,?,?,0,?,1,?,0,?,NULL,?)",
                            arrayOf<Any?>(
                                a, request.teamId, request.folderId, request.localRole,
                                request.ownerSubjectHash, request.headSnapshotId ?: "",
                                TeamWorkspaceStatus.LOCAL_ONLY,
                                if (request.localRole == TeamRole.EDITOR || request.localRole == TeamRole.OWNER) 1 else 0,
                                now, now,
                            ),
                        )
                        db.execSQL(
                            "INSERT OR REPLACE INTO team_members VALUES(?,?,?,?,?,'ACTIVE',?)",
                            arrayOf<Any?>(
                                "owner:${request.teamId}:${request.ownerEmail}",
                                a, request.ownerEmail, request.ownerDisplayName ?: "",
                                request.localRole, now,
                            ),
                        )
                    }
                    validateConversionState(db, accountIds)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            applyStagingToLive(stagingDb, accountIds)
            return ConversionResult(accountIds, backupPath)
        } finally {
            stagingDb.delete()
            File(stagingDb.path + "-wal").delete()
            File(stagingDb.path + "-shm").delete()
        }
    }

    suspend fun convertTeamToPrivate(accountIds: List<Long>): ConversionResult {
        require(accountIds.isNotEmpty()) { "Tidak ada akun yang akan dikonversi" }
        validateTeamAccounts(accountIds)
        val now = System.currentTimeMillis()
        val backupPath = createPreConversionBackup()
        val stagingDb = File(context.cacheDir, "team-downgrade-${UUID.randomUUID()}.db")
        try {
            copyLiveToStaging(stagingDb)
            SQLiteDatabase.openDatabase(stagingDb.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("PRAGMA foreign_keys=ON")
                db.beginTransaction()
                try {
                    for (accountId in accountIds) {
                        val a = accountId.toString()
                        db.execSQL(
                            "UPDATE accounts SET sharingMode=?, teamId=NULL, updatedAt=? WHERE id=?",
                            arrayOf<Any?>(AccountSharingMode.PRIVATE, now, a),
                        )
                        db.execSQL("DELETE FROM team_workspaces WHERE accountId=?", arrayOf<Any?>(a))
                        db.execSQL("DELETE FROM team_members WHERE accountId=?", arrayOf<Any?>(a))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            applyStagingToLive(stagingDb, accountIds)
            return ConversionResult(accountIds, backupPath)
        } finally {
            stagingDb.delete()
            File(stagingDb.path + "-wal").delete()
            File(stagingDb.path + "-shm").delete()
        }
    }

    private fun validatePrivateAccounts(accountIds: List<Long>) {
        val liveWritable = database.openHelper.writableDatabase as android.database.sqlite.SQLiteDatabase
        for (aid in accountIds) {
            val row = liveWritable.rawQuery(
                "SELECT sharingMode, teamId FROM accounts WHERE id=?",
                arrayOf(aid.toString()),
            ).use { cursor ->
                require(cursor.moveToFirst()) { "Akun $aid tidak ditemukan" }
                cursor.getString(0) to cursor.getString(1)
            }
            require(row.first == AccountSharingMode.PRIVATE) { "Akun $aid sudah Team" }
            require(row.second.isNullOrBlank()) { "Akun $aid sudah memiliki teamId" }
        }
    }

    private fun validateTeamAccounts(accountIds: List<Long>) {
        val liveWritable = database.openHelper.writableDatabase as android.database.sqlite.SQLiteDatabase
        for (aid in accountIds) {
            val sharingMode = liveWritable.rawQuery(
                "SELECT sharingMode FROM accounts WHERE id=?",
                arrayOf(aid.toString()),
            ).use { cursor ->
                require(cursor.moveToFirst()) { "Akun $aid tidak ditemukan" }
                cursor.getString(0)
            }
            require(sharingMode == AccountSharingMode.TEAM) { "Akun $aid bukan Team" }
        }
    }

    private fun validateConversionState(db: SQLiteDatabase, accountIds: List<Long>) {
        for (aid in accountIds) {
            val a = aid.toString()
            val row = db.rawQuery(
                "SELECT sharingMode, teamId FROM accounts WHERE id=?",
                arrayOf(a),
            ).use { c ->
                require(c.moveToFirst()) { "Akun $aid hilang setelah konversi" }
                c.getString(0) to c.getString(1)
            }
            require(row.first == AccountSharingMode.TEAM) { "sharingMode akun $aid tidak berubah" }
            require(!row.second.isNullOrBlank()) { "teamId akun $aid kosong" }
            val wsTeamId = db.rawQuery(
                "SELECT teamId FROM team_workspaces WHERE accountId=?",
                arrayOf(a),
            ).use { c ->
                require(c.moveToFirst()) { "Workspace akun $aid tidak ditemukan" }
                c.getString(0)
            }
            require(wsTeamId == row.second) { "teamId akun $aid tidak cocok dengan workspace" }
        }
    }

    private fun createPreConversionBackup(): String {
        val backupDir = File(context.noBackupFilesDir, "pre-conversion")
        backupDir.mkdirs()
        val liveDb = context.getDatabasePath(KronDatabase.DATABASE_NAME)
        val backupFile = File(backupDir, "database-${uuid()}.db")
        liveDb.inputStream().use { i -> backupFile.outputStream().use { o -> i.copyTo(o) } }
        return backupFile.absolutePath
    }

    private fun copyLiveToStaging(stagingDb: File) {
        stagingDb.parentFile?.mkdirs()
        val liveDb = context.getDatabasePath(KronDatabase.DATABASE_NAME)
        liveDb.inputStream().use { i -> stagingDb.outputStream().use { o -> i.copyTo(o) } }
        File(liveDb.path + "-wal").takeIf { it.exists() }?.let {
            it.copyTo(File(stagingDb.path + "-wal"), overwrite = true)
        }
        File(liveDb.path + "-shm").takeIf { it.exists() }?.let {
            it.copyTo(File(stagingDb.path + "-shm"), overwrite = true)
        }
        require(stagingDb.exists()) { "Staging database konversi tidak terbentuk" }
    }

    private fun applyStagingToLive(stagingDb: File, accountIds: List<Long>) {
        val liveWritable = database.openHelper.writableDatabase
        liveWritable.execSQL("ATTACH DATABASE ? AS convert_db", arrayOf(stagingDb.absolutePath))
        try {
            liveWritable.beginTransaction()
            try {
                for (aid in accountIds) {
                    val a = aid.toString()
                    liveWritable.execSQL(
                        "UPDATE accounts SET sharingMode=(SELECT sharingMode FROM convert_db.accounts WHERE id=?), teamId=(SELECT teamId FROM convert_db.accounts WHERE id=?), updatedAt=(SELECT updatedAt FROM convert_db.accounts WHERE id=?) WHERE id=?",
                        arrayOf(a, a, a, a),
                    )
                }
                liveWritable.execSQL("INSERT OR IGNORE INTO team_workspaces SELECT * FROM convert_db.team_workspaces")
                liveWritable.execSQL("DELETE FROM team_workspaces WHERE accountId IN (SELECT id FROM convert_db.accounts WHERE sharingMode='PRIVATE')")
                liveWritable.execSQL("INSERT OR IGNORE INTO team_members SELECT * FROM convert_db.team_members")
                liveWritable.execSQL("DELETE FROM team_members WHERE accountId IN (SELECT id FROM convert_db.accounts WHERE sharingMode='PRIVATE')")
                database.invalidationTracker.refreshAsync()
                liveWritable.setTransactionSuccessful()
            } finally {
                liveWritable.endTransaction()
            }
        } finally {
            liveWritable.execSQL("DETACH DATABASE convert_db")
        }
    }

    companion object {
        private fun uuid(): String = UUID.randomUUID().toString().replace("-", "")
    }
}
