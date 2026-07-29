package com.morneven.kron.team

import com.morneven.kron.BuildConfig
import com.morneven.kron.audit.EvidenceSigningKeyManager
import com.morneven.kron.backup.BackupManager
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.TeamRole
import com.morneven.kron.sync.GoogleAccountIdentity
import com.morneven.kron.sync.RemoteDriveSnapshot
import com.morneven.kron.sync.SnapshotDag
import javax.inject.Inject
import javax.inject.Singleton

class TeamJoinPreflightResult(
    val teamId: String,
    val folderId: String,
    val role: String,
    val headSnapshotId: String,
    val generation: Long,
    val liveFileId: String,
) {
    override fun toString(): String = "TeamJoinPreflightResult(redacted)"
}

@Singleton
class TeamJoinPreflight @Inject constructor(
    private val database: KronDatabase,
    private val drive: TeamDriveRestClient,
    private val signingKeys: EvidenceSigningKeyManager,
    private val snapshotCryptor: TeamSnapshotCryptor,
    private val backupManager: BackupManager,
) {
    suspend fun verifyReadOnly(
        accessToken: String,
        googleAccount: GoogleAccountIdentity,
        code: String,
    ): TeamJoinPreflightResult {
        check(BuildConfig.TEAM_ACCOUNT_ENABLED) { "Team Account belum aktif pada build ini" }
        val invitation = TeamInvitationCodec.decode(code)
        var snapshotEnvelope = ByteArray(0)
        var teamKey = ByteArray(0)
        var payload = ByteArray(0)
        try {
            require(TeamInvitationCodec.emailMatches(invitation, googleAccount.email)) {
                "Kode akses Team bukan untuk akun Google ini"
            }
            val inviteHash = TeamInvitationCodec.sha256(invitation.inviteId.toByteArray(Charsets.UTF_8))
            require(!database.kronDao().teamInvitationWasUsed(inviteHash)) { "Kode akses Team sudah pernah digunakan" }

            val liveFileId = requireNotNull(invitation.liveFileId) {
                "Kode akses lama tidak didukung untuk drive.file. Minta Owner membuat undangan baru."
            }
            val invitationEnvelope = requireNotNull(invitation.embeddedEnvelopeCopy()) {
                "Envelope kode akses Team tidak tersedia"
            }
            try {
                teamKey = TeamInvitationEnvelopeCrypto.open(invitation, invitationEnvelope, signingKeys).teamKey
            } finally {
                invitationEnvelope.fill(0)
            }
            val head = drive.stableSnapshot(accessToken, liveFileId, invitation.teamId)
            require(head.manifest.minimumAppVersionCode <= BuildConfig.VERSION_CODE) {
                "Snapshot Team memerlukan versi KRON yang lebih baru"
            }
            snapshotEnvelope = drive.download(accessToken, head.fileId)
            val opened = snapshotCryptor.decrypt(snapshotEnvelope, teamKey)
            payload = opened.payload
            require(opened.manifest == head.manifest) { "Metadata snapshot Team tidak cocok" }
            val scope = backupManager.validateTeamSnapshotPayload(payload, invitation.teamId)
            require(scope.generation == head.manifest.generation) { "Generation snapshot Team tidak cocok" }
            return TeamJoinPreflightResult(
                teamId = invitation.teamId,
                folderId = invitation.folderId,
                role = invitation.role,
                headSnapshotId = head.manifest.snapshotId,
                generation = head.manifest.generation,
                liveFileId = liveFileId,
            )
        } finally {
            invitation.clear()
            snapshotEnvelope.fill(0)
            teamKey.fill(0)
            payload.fill(0)
        }
    }
}
/** Legacy-code validator kept so old invitations remain diagnosable. Runtime joins use the stable file path above. */
internal object TeamJoinPolicy {
    fun requireCapabilities(invitation: TeamInvitation, workspace: TeamDriveWorkspace) {
        require(workspace.folderId == invitation.folderId && workspace.capabilities.canRead &&
            !workspace.capabilities.canShare && !workspace.writersCanShare) { "Capability workspace Team tidak aman" }
        require((invitation.role == TeamRole.EDITOR) == workspace.capabilities.canWrite) {
            "Role undangan tidak cocok dengan capability Drive"
        }
    }

    fun invitationCandidates(files: List<TeamDriveFile>, invitation: TeamInvitation): List<TeamDriveFile> {
        val name = "invitation-${TeamInvitationCodec.sha256(invitation.inviteId.toByteArray(Charsets.UTF_8))}.kronteam"
        return files.filter { it.sizeBytes > 0 && it.name == name }.sortedBy(TeamDriveFile::fileId)
            .ifEmpty { throw IllegalArgumentException("File undangan Team tidak ditemukan") }
    }

    fun snapshotHead(snapshots: List<RemoteDriveSnapshot>, teamId: String): RemoteDriveSnapshot {
        val dag = SnapshotDag.inspect(snapshots, teamId)
        require(dag.valid && dag.heads.size == 1) { "Snapshot Team bercabang atau tidak valid" }
        return dag.heads.single()
    }
}
