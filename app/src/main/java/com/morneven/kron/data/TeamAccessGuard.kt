package com.morneven.kron.data

import javax.inject.Inject
import javax.inject.Singleton

enum class TeamCapability {
    READ,
    WRITE,
    AUTOMATION,
    RESTORE,
    REPORT_EXPORT,
    PORTABLE_BACKUP,
    MANAGE_MEMBERS,
    CONVERT_ACCOUNT,
}

class TeamAccessDeniedException(message: String) : IllegalStateException(message)

@Singleton
class TeamAccessGuard @Inject constructor(
    private val database: KronDatabase,
) {
    suspend fun requireActive(capability: TeamCapability) {
        val accountId = database.kronDao().activeAccount()?.id
            ?: throw TeamAccessDeniedException("Tidak ada akun aktif")
        require(accountId, capability)
    }

    suspend fun requireNoTeamAccounts() {
        if (database.kronDao().teamAccountCount() > 0) {
            throw TeamAccessDeniedException("Operasi seluruh database tidak tersedia saat Team Account aktif")
        }
    }

    suspend fun require(accountId: Long, capability: TeamCapability) {
        val dao = database.kronDao()
        val account = dao.accountById(accountId)
            ?: throw TeamAccessDeniedException("Akun tidak ditemukan")
        if (account.sharingMode == AccountSharingMode.PRIVATE) return
        if (account.sharingMode != AccountSharingMode.TEAM || account.teamId.isNullOrBlank()) {
            throw TeamAccessDeniedException("Status Team Account tidak valid")
        }
        val workspace = dao.teamWorkspace(accountId)
            ?.takeIf { it.teamId == account.teamId }
            ?: throw TeamAccessDeniedException("Workspace Team belum tervalidasi")
        if (workspace.status in setOf(TeamWorkspaceStatus.REVOKED, TeamWorkspaceStatus.ARCHIVED)) {
            throw TeamAccessDeniedException("Akses Team Account sudah tidak aktif")
        }

        TeamAccessPolicy.require(workspace, capability)
    }

    suspend fun requireTransfer(fromAccountId: Long, toAccountId: Long) {
        val dao = database.kronDao()
        val from = dao.accountById(fromAccountId) ?: throw TeamAccessDeniedException("Akun sumber tidak ditemukan")
        val to = dao.accountById(toAccountId) ?: throw TeamAccessDeniedException("Akun tujuan tidak ditemukan")
        TeamAccessPolicy.requireIsolatedTransfer(from, to)
    }

    suspend fun allows(accountId: Long, capability: TeamCapability): Boolean = try {
        require(accountId, capability)
        true
    } catch (_: TeamAccessDeniedException) {
        false
    }
}

internal object TeamAccessPolicy {
    fun requireIsolatedTransfer(from: AccountEntity, to: AccountEntity) {
        if (from.id != to.id && (from.sharingMode == AccountSharingMode.TEAM || to.sharingMode == AccountSharingMode.TEAM)) {
            throw TeamAccessDeniedException("Transfer antar Team Account dan akun lain belum didukung")
        }
    }

    fun require(workspace: TeamWorkspaceEntity, capability: TeamCapability) {
        if (workspace.localRole !in setOf(TeamRole.OWNER, TeamRole.EDITOR, TeamRole.VIEWER)) {
            throw TeamAccessDeniedException("Role Team Account tidak valid")
        }
        if (capability == TeamCapability.READ) {
            if (!workspace.canRead || workspace.capabilitiesVerifiedAt == null) {
                throw TeamAccessDeniedException("Izin baca Drive belum tervalidasi")
            }
            return
        }
        val ownerOnly = capability in setOf(
            TeamCapability.PORTABLE_BACKUP,
            TeamCapability.MANAGE_MEMBERS,
            TeamCapability.CONVERT_ACCOUNT,
        )
        if (ownerOnly && workspace.localRole != TeamRole.OWNER) {
            throw TeamAccessDeniedException("Tindakan ini hanya tersedia untuk Owner")
        }
        if (workspace.localRole == TeamRole.VIEWER) {
            throw TeamAccessDeniedException("Akun ini memiliki akses hanya lihat")
        }
        if (!workspace.canRead || !workspace.canWrite || workspace.capabilitiesVerifiedAt == null) {
            throw TeamAccessDeniedException("Izin tulis Drive belum tervalidasi")
        }
        if (capability == TeamCapability.MANAGE_MEMBERS && !workspace.canShare) {
            throw TeamAccessDeniedException("Drive tidak mengizinkan pengelolaan collaborator")
        }
    }
}
