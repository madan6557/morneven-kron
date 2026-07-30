package com.morneven.kron.team

import com.morneven.kron.BuildConfig
import com.morneven.kron.audit.EvidenceSigningKeyManager
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.security.DatabaseRuntime
import com.morneven.kron.data.TeamAccessGuard
import com.morneven.kron.data.TeamCapability
import com.morneven.kron.data.TeamMemberEntity
import com.morneven.kron.data.TeamRole
import com.morneven.kron.data.TeamWorkspaceEntity
import com.morneven.kron.sync.GoogleAccountIdentity
import androidx.room.withTransaction
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class CreatedTeamInvitation(
    val code: String,
    val snapshotFileId: String,
    val member: TeamDriveMember,
) {
    override fun toString(): String = "CreatedTeamInvitation(redacted)"
}

@Singleton
class TeamInvitationManager @Inject constructor(
    private val databaseRuntime: DatabaseRuntime,
    private val accessGuard: TeamAccessGuard,
    private val keyStore: TeamKeyStore,
    private val signingKeys: EvidenceSigningKeyManager,
    private val drive: TeamDriveRestClient,
) {
    private val database get() = databaseRuntime.current()

    suspend fun create(
        accessToken: String,
        googleAccount: GoogleAccountIdentity,
        accountId: Long,
        targetEmail: String,
        role: String,
    ): CreatedTeamInvitation {
        val workspace = requireOwnerWorkspace(accessToken, googleAccount, accountId)
        require(!workspace.headSnapshotId.isNullOrBlank() && !workspace.liveFileId.isNullOrBlank()) {
            "Sinkronkan Team Owner terlebih dahulu sebelum membuat undangan"
        }
        val dao = database.kronDao()

        val invitation = TeamInvitationCodec.create(
            teamId = workspace.teamId,
            folderId = workspace.folderId,
            targetEmail = targetEmail,
            role = role,
            ownerKeyFingerprint = signingKeys.publicRecord().fingerprint,
            liveFileId = workspace.liveFileId,
        )
        val teamKey = requireNotNull(keyStore.acquire(workspace.teamId)) { "Team key tidak tersedia" }
        var envelope = ByteArray(0)
        var member: TeamDriveMember? = null
        try {
            envelope = TeamInvitationEnvelopeCrypto.seal(invitation, teamKey, signingKeys)
            val code = TeamInvitationCodec.encode(invitation, envelope)
            val granted = drive.addMember(accessToken, workspace.folderId, targetEmail, role)
            member = granted
            require(granted.role == role && TeamInvitationCodec.emailMatches(invitation, granted.email)) {
                "Identitas atau role collaborator Drive tidak cocok"
            }
            dao.upsertTeamMembers(listOf(TeamMemberPolicy.toEntity(accountId, granted)))
            return CreatedTeamInvitation(code, requireNotNull(workspace.liveFileId), granted)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                member?.let { granted ->
                    runCatching { drive.removeMember(accessToken, workspace.folderId, granted.permissionId) }
                        .onFailure(error::addSuppressed)
                }
            }
            throw error
        } finally {
            invitation.clear()
            teamKey.fill(0)
            envelope.fill(0)
        }
    }

    suspend fun refreshMembers(
        accessToken: String,
        googleAccount: GoogleAccountIdentity,
        accountId: Long,
    ): List<TeamMemberEntity> {
        val workspace = requireOwnerWorkspace(accessToken, googleAccount, accountId)
        val members = TeamMemberPolicy.toEntities(accountId, drive.listMembers(accessToken, workspace.folderId))
        database.withTransaction {
            database.kronDao().clearTeamMembers(accountId)
            database.kronDao().upsertTeamMembers(members)
        }
        return members
    }

    suspend fun changeRole(
        accessToken: String,
        googleAccount: GoogleAccountIdentity,
        accountId: Long,
        permissionId: String,
        role: String,
    ): TeamMemberEntity {
        val workspace = requireOwnerWorkspace(accessToken, googleAccount, accountId)
        val current = requireNotNull(database.kronDao().teamMembers(accountId).singleOrNull { it.permissionId == permissionId }) {
            "Collaborator Team tidak ditemukan"
        }
        TeamMemberPolicy.requireMutable(current, role)
        val updated = drive.updateMemberRole(accessToken, workspace.folderId, permissionId, role)
        require(updated.permissionId == permissionId && updated.role == role) { "Role collaborator Drive tidak cocok" }
        val entity = TeamMemberPolicy.toEntity(accountId, updated)
        withContext(NonCancellable) { database.kronDao().upsertTeamMembers(listOf(entity)) }
        return entity
    }

    suspend fun removeMember(
        accessToken: String,
        googleAccount: GoogleAccountIdentity,
        accountId: Long,
        permissionId: String,
    ) {
        val workspace = requireOwnerWorkspace(accessToken, googleAccount, accountId)
        val current = requireNotNull(database.kronDao().teamMembers(accountId).singleOrNull { it.permissionId == permissionId }) {
            "Collaborator Team tidak ditemukan"
        }
        TeamMemberPolicy.requireMutable(current, current.role)
        drive.removeMember(accessToken, workspace.folderId, permissionId)
        withContext(NonCancellable) { database.kronDao().deleteTeamMember(accountId, permissionId) }
    }

    private suspend fun requireOwnerWorkspace(
        accessToken: String,
        googleAccount: GoogleAccountIdentity,
        accountId: Long,
    ): TeamWorkspaceEntity {
        check(BuildConfig.TEAM_ACCOUNT_ENABLED) { "Team Account belum aktif pada build ini" }
        accessGuard.require(accountId, TeamCapability.MANAGE_MEMBERS)
        val dao = database.kronDao()
        val account = requireNotNull(dao.accountById(accountId)) { "Team Account tidak ditemukan" }
        val workspace = requireNotNull(dao.teamWorkspace(accountId)) { "Workspace Team tidak ditemukan" }
        require(account.teamId == workspace.teamId) { "Workspace Team tidak cocok" }
        val subjectHash = MessageDigest.getInstance("SHA-256")
            .digest(googleAccount.subjectId.toByteArray(Charsets.UTF_8))
        require(MessageDigest.isEqual(subjectHash, workspace.ownerSubjectHash.hexBytes())) {
            "Akun Google bukan Owner workspace Team"
        }
        val remote = drive.workspace(accessToken, workspace.folderId)
        require(
            remote.folderId == workspace.folderId && remote.capabilities.canRead &&
                remote.capabilities.canWrite && remote.capabilities.canShare && !remote.writersCanShare,
        ) {
            "Drive tidak mengizinkan pengelolaan collaborator"
        }
        return workspace
    }

    private fun String.hexBytes(): ByteArray {
        require(matches(Regex("[0-9a-f]{64}"))) { "Identitas Owner Team tidak valid" }
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}

internal object TeamMemberPolicy {
    fun toEntities(
        accountId: Long,
        members: List<TeamDriveMember>,
        refreshedAt: Long = System.currentTimeMillis(),
    ): List<TeamMemberEntity> {
        require(members.map(TeamDriveMember::permissionId).distinct().size == members.size) {
            "Drive mengembalikan permission collaborator duplikat"
        }
        require(members.count { it.role == TeamRole.OWNER } == 1) { "Owner workspace Team tidak valid" }
        return members.map { toEntity(accountId, it, refreshedAt) }
    }

    fun toEntity(
        accountId: Long,
        member: TeamDriveMember,
        refreshedAt: Long = System.currentTimeMillis(),
    ): TeamMemberEntity {
        require(accountId > 0 && member.permissionId.isNotBlank()) { "Collaborator Team tidak valid" }
        require(member.role in setOf(TeamRole.OWNER, TeamRole.EDITOR, TeamRole.VIEWER)) { "Role collaborator tidak valid" }
        TeamInvitationCodec.emailHash(member.email)
        return TeamMemberEntity(
            permissionId = member.permissionId,
            accountId = accountId,
            email = member.email.trim().lowercase(Locale.ROOT),
            displayName = member.displayName,
            role = member.role,
            status = "ACTIVE",
            refreshedAt = refreshedAt,
        )
    }

    fun requireMutable(member: TeamMemberEntity, newRole: String) {
        require(member.role != TeamRole.OWNER) { "Owner workspace Team tidak dapat diubah atau dihapus" }
        require(newRole == TeamRole.EDITOR || newRole == TeamRole.VIEWER) { "Role collaborator tidak valid" }
    }
}
