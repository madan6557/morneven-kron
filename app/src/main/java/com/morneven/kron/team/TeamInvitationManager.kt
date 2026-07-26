package com.morneven.kron.team

import com.morneven.kron.BuildConfig
import com.morneven.kron.audit.EvidenceSigningKeyManager
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.TeamAccessGuard
import com.morneven.kron.data.TeamCapability
import com.morneven.kron.data.TeamMemberEntity
import com.morneven.kron.sync.GoogleAccountIdentity
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class CreatedTeamInvitation(
    val code: String,
    val invitationFileId: String,
    val member: TeamDriveMember,
) {
    override fun toString(): String = "CreatedTeamInvitation(redacted)"
}

@Singleton
class TeamInvitationManager @Inject constructor(
    private val database: KronDatabase,
    private val accessGuard: TeamAccessGuard,
    private val keyStore: TeamKeyStore,
    private val signingKeys: EvidenceSigningKeyManager,
    private val drive: TeamDriveRestClient,
) {
    suspend fun create(
        accessToken: String,
        googleAccount: GoogleAccountIdentity,
        accountId: Long,
        targetEmail: String,
        role: String,
    ): CreatedTeamInvitation {
        check(BuildConfig.TEAM_ACCOUNT_ENABLED) { "Team Account belum aktif pada build ini" }
        accessGuard.require(accountId, TeamCapability.MANAGE_MEMBERS)
        val dao = database.kronDao()
        val account = requireNotNull(dao.accountById(accountId)) { "Team Account tidak ditemukan" }
        val workspace = requireNotNull(dao.teamWorkspace(accountId)) { "Workspace Team tidak ditemukan" }
        require(account.teamId == workspace.teamId) { "Workspace Team tidak cocok" }
        val subjectHash = MessageDigest.getInstance("SHA-256")
            .digest(googleAccount.subjectId.toByteArray(Charsets.UTF_8))
        require(
            MessageDigest.isEqual(subjectHash, workspace.ownerSubjectHash.hexBytes()),
        ) { "Akun Google bukan Owner workspace Team" }
        val remoteWorkspace = drive.workspace(accessToken, workspace.folderId)
        require(remoteWorkspace.capabilities.canShare && !remoteWorkspace.writersCanShare) {
            "Drive tidak mengizinkan pengelolaan collaborator"
        }

        val invitation = TeamInvitationCodec.create(
            teamId = workspace.teamId,
            folderId = workspace.folderId,
            targetEmail = targetEmail,
            role = role,
            ownerKeyFingerprint = signingKeys.publicRecord().fingerprint,
        )
        val teamKey = requireNotNull(keyStore.acquire(workspace.teamId)) { "Team key tidak tersedia" }
        var envelope = ByteArray(0)
        var invitationFile: TeamDriveFile? = null
        var member: TeamDriveMember? = null
        try {
            val code = TeamInvitationCodec.encode(invitation)
            envelope = TeamInvitationEnvelopeCrypto.seal(invitation, teamKey, signingKeys)
            val uploaded = drive.uploadInvitation(accessToken, workspace.folderId, invitation, envelope)
            invitationFile = uploaded
            val granted = drive.addMember(accessToken, workspace.folderId, targetEmail, role)
            member = granted
            require(granted.role == role) { "Role collaborator Drive tidak cocok" }
            dao.upsertTeamMembers(
                listOf(
                    TeamMemberEntity(
                        permissionId = granted.permissionId,
                        accountId = accountId,
                        email = targetEmail.trim().lowercase(Locale.ROOT),
                        displayName = granted.displayName,
                        role = granted.role,
                        status = "ACTIVE",
                    ),
                ),
            )
            return CreatedTeamInvitation(code, uploaded.fileId, granted)
        } catch (error: Exception) {
            withContext(NonCancellable) {
                member?.let { granted ->
                    runCatching { drive.removeMember(accessToken, workspace.folderId, granted.permissionId) }
                        .onFailure(error::addSuppressed)
                }
                invitationFile?.let { uploaded ->
                    runCatching { drive.delete(accessToken, uploaded.fileId) }
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

    private fun String.hexBytes(): ByteArray {
        require(matches(Regex("[0-9a-f]{64}"))) { "Identitas Owner Team tidak valid" }
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }
}
