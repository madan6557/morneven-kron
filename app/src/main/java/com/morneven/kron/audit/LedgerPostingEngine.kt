package com.morneven.kron.audit

import android.content.Context
import androidx.room.withTransaction
import com.morneven.kron.BuildConfig
import com.morneven.kron.data.ActivityEventEntity
import com.morneven.kron.data.ActorProfileEntity
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.JournalSealEntity
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.LedgerAccountEntity
import com.morneven.kron.data.LedgerAccountKind
import com.morneven.kron.data.LedgerLineEntity
import com.morneven.kron.data.LedgerSide
import com.morneven.kron.data.LedgerType
import com.morneven.kron.data.AccountSharingMode
import com.morneven.kron.data.TeamEventProofEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZoneId
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LedgerPostingEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: KronDatabase,
    private val signingKeys: EvidenceSigningKeyManager,
) {
    private val dao get() = database.kronDao()

    suspend fun finalizeUnsealedEvents() = database.withTransaction {
        dao.insertActorProfile(ActorProfileEntity())
        val previousKeys = dao.allEvidenceKeys()
        val key = signingKeys.publicRecord()
        val rotated = previousKeys.isNotEmpty() && previousKeys.none { it.id == key.id }
        dao.insertEvidenceKey(key)
        ensureBaseAccounts()
        if (rotated) {
            val rotationId = UUID.randomUUID().toString()
            dao.insertEvent(
                ActivityEventEntity(
                    id = rotationId,
                    type = LedgerType.EVIDENCE_KEY_ROTATION,
                    title = "Kunci tanda tangan bukti dirotasi",
                    note = "Kunci perangkat baru digunakan. Sertifikat publik lama tetap dipertahankan untuk verifikasi.",
                    source = "SYSTEM",
                    effectiveEpochDay = LocalDate.now().toEpochDay(),
                    accountId = requireNotNull(dao.activeAccount()) { "Tidak ada akun aktif untuk event rotasi kunci" }.id,
                ),
            )
            validateEvent(rotationId)
            seal(requireNotNull(dao.eventById(rotationId)), key.id)
        }
        dao.unsealedEvents().forEach { event ->
            ensureLedgerLines(event)
            validateEvent(event.id)
            seal(event, key.id)
        }
        dao.teamEventsWithoutProof().forEach { event -> sealTeamEvent(event, key.id) }
    }

    suspend fun finalizeEvent(eventId: String) = database.withTransaction {
        dao.sealForEvent(eventId)?.let {
            val event = requireNotNull(dao.eventById(eventId)) { "Event jurnal tidak ditemukan" }
            if (dao.teamEventProof(eventId) == null && dao.accountById(event.accountId)?.sharingMode == AccountSharingMode.TEAM) {
                val key = signingKeys.publicRecord()
                dao.insertEvidenceKey(key)
                sealTeamEvent(event, key.id)
            }
            return@withTransaction
        }
        dao.insertActorProfile(ActorProfileEntity())
        require(dao.allEvidenceKeys().isEmpty() || signingKeys.hasKey()) {
            "Kunci tanda tangan bukti tidak tersedia. Penulisan jurnal diblokir untuk melindungi rantai audit."
        }
        val key = signingKeys.publicRecord()
        dao.insertEvidenceKey(key)
        ensureBaseAccounts()
        val event = requireNotNull(dao.eventById(eventId)) { "Event jurnal tidak ditemukan" }
        ensureLedgerLines(event)
        validateEvent(event.id)
        seal(event, key.id)
        if (dao.teamEventProof(event.id) == null && dao.accountById(event.accountId)?.sharingMode == AccountSharingMode.TEAM) {
            sealTeamEvent(event, key.id)
        }
    }

    suspend fun validateAll() {
        finalizeUnsealedEvents()
        val unbalanced = dao.unbalancedLedgerEvents()
        require(unbalanced.isEmpty()) { "General ledger memiliki event tidak seimbang" }
        verifySealChain()
    }

    suspend fun verifySealChain() {
        var previous = GENESIS_HASH
        var expectedSequence = 1L
        dao.allJournalSeals().forEach { seal ->
            require(seal.sequence == expectedSequence) { "Urutan seal jurnal tidak valid" }
            require(seal.previousChainHash == previous) { "Rantai hash jurnal terputus" }
            val event = requireNotNull(dao.eventById(seal.eventId))
            val payloadHash = payloadHash(event)
            require(payloadHash == seal.payloadHash) { "Payload jurnal berubah setelah disegel" }
            val expectedChain = chainHash(previous, payloadHash, seal.sequence)
            require(expectedChain == seal.chainHash) { "Hash rantai jurnal tidak valid" }
            val key = requireNotNull(dao.evidenceKeyById(seal.keyId))
            require(signingKeys.verify(expectedChain.toByteArray(StandardCharsets.UTF_8), seal.signatureBase64, key.certificateBase64)) {
                "Tanda tangan jurnal tidak valid"
            }
            previous = seal.chainHash
            expectedSequence++
        }
        verifyTeamProofChains()
    }

    private suspend fun verifyTeamProofChains() {
        dao.allTeamEventProofs().groupBy { it.chainId }.values.forEach { chain ->
            var previous = GENESIS_HASH
            chain.forEachIndexed { index, proof ->
                require(proof.canonicalVersion == TeamLedgerCanonicalizer.VERSION) {
                    "Versi bukti event Team tidak didukung"
                }
                require(proof.sequence == index + 1L) { "Urutan bukti event Team tidak valid" }
                require(proof.previousChainHash == previous) { "Rantai bukti event Team terputus" }
                require(proof.chainId == TeamLedgerCanonicalizer.chainId(proof.teamId, proof.deviceId)) {
                    "Identitas rantai bukti Team tidak valid"
                }
                val event = requireNotNull(dao.eventById(proof.eventId)) { "Event bukti Team tidak ditemukan" }
                require(teamPayloadHash(event, proof.teamId) == proof.payloadHash) {
                    "Payload event Team berubah setelah disegel"
                }
                val expected = TeamLedgerCanonicalizer.chainHash(
                    teamId = proof.teamId,
                    chainId = proof.chainId,
                    previousChainHash = previous,
                    payloadHash = proof.payloadHash,
                    sequence = proof.sequence,
                    recordedAtUtc = proof.recordedAtUtc,
                    deviceId = proof.deviceId,
                    actor = proof.actor,
                    appVersion = proof.appVersion,
                    keyId = proof.keyId,
                )
                require(expected == proof.chainHash) { "Hash rantai bukti Team tidak valid" }
                val key = requireNotNull(dao.evidenceKeyById(proof.keyId)) { "Kunci bukti Team tidak ditemukan" }
                require(
                    signingKeys.verify(
                        expected.toByteArray(StandardCharsets.UTF_8),
                        proof.signatureBase64,
                        key.certificateBase64,
                    ),
                ) { "Tanda tangan bukti Team tidak valid" }
                previous = proof.chainHash
            }
        }
    }

    private suspend fun ensureBaseAccounts() {
        dao.insertLedgerAccounts(
            listOf(
                LedgerAccountEntity("income:general", "4000", "Pemasukan", LedgerAccountKind.INCOME),
                LedgerAccountEntity("expense:general", "5000", "Pengeluaran", LedgerAccountKind.EXPENSE),
                LedgerAccountEntity("equity:opening", "3000", "Modal awal", LedgerAccountKind.EQUITY),
                LedgerAccountEntity("clearing:legacy", "9999", "Legacy clearing", LedgerAccountKind.CLEARING),
            ),
        )
        dao.allAccounts().forEach { account ->
            listOf(FundingChannel.CASH, FundingChannel.EBUDGET).forEach { channel ->
                dao.insertLedgerAccount(assetAccount(account.id, account.name, channel))
            }
        }
    }

    private suspend fun ensureLedgerLines(event: ActivityEventEntity) {
        if (dao.ledgerLinesForEvent(event.id).isNotEmpty()) return
        val cash = dao.cashLinesForEvent(event.id).filter { it.amount != 0L }
        if (cash.isEmpty()) return
        val lines = when (event.type) {
            LedgerType.TRANSFER, LedgerType.CHANNEL_TRANSFER -> cash.map {
                assetLine(event, it.accountId, it.fundingChannel, it.amount)
            }.balancedWithClearing(event)
            LedgerType.REVERSAL -> {
                val original = event.relatedEventId?.let { dao.ledgerLinesForEvent(it) }.orEmpty()
                if (original.isNotEmpty()) original.map {
                    it.copy(
                        id = 0,
                        eventId = event.id,
                        side = if (it.side == LedgerSide.DEBIT) LedgerSide.CREDIT else LedgerSide.DEBIT,
                        correlationId = event.relatedEventId,
                        legacyBackfill = false,
                    )
                } else ordinaryCashLines(event, cash, legacy = true)
            }
            else -> ordinaryCashLines(event, cash, legacy = false)
        }
        if (lines.isNotEmpty()) dao.insertLedgerLines(lines)
    }

    private suspend fun ordinaryCashLines(
        event: ActivityEventEntity,
        cash: List<com.morneven.kron.data.CashJournalLineEntity>,
        legacy: Boolean,
    ): List<LedgerLineEntity> {
        val splits = dao.splitsForEvent(event.id)
        return buildList {
            cash.forEach { cashLine ->
                add(assetLine(event, cashLine.accountId, cashLine.fundingChannel, cashLine.amount, legacy))
                val counterpartSide = if (cashLine.amount > 0) LedgerSide.CREDIT else LedgerSide.DEBIT
                val expected = kotlin.math.abs(cashLine.amount)
                val matchingSplits = splits.filter { it.amount > 0 }
                if (matchingSplits.isNotEmpty() && matchingSplits.sumOf { it.amount } == expected) {
                    matchingSplits.forEach { split ->
                        val account = counterpartAccount(event, cashLine.amount, split.categoryId, legacy)
                        dao.insertLedgerAccount(account)
                        add(
                            LedgerLineEntity(
                                eventId = event.id,
                                ledgerAccountId = account.id,
                                side = counterpartSide,
                                amount = split.amount,
                                accountId = cashLine.accountId,
                                fundingChannel = cashLine.fundingChannel,
                                categoryId = split.categoryId,
                                correlationId = event.relatedEventId,
                                legacyBackfill = legacy,
                            ),
                        )
                    }
                } else {
                    val account = counterpartAccount(event, cashLine.amount, null, legacy)
                    dao.insertLedgerAccount(account)
                    add(
                        LedgerLineEntity(
                            eventId = event.id,
                            ledgerAccountId = account.id,
                            side = counterpartSide,
                            amount = expected,
                            accountId = cashLine.accountId,
                            fundingChannel = cashLine.fundingChannel,
                            correlationId = event.relatedEventId,
                            legacyBackfill = legacy,
                        ),
                    )
                }
            }
        }
    }

    private fun counterpartAccount(event: ActivityEventEntity, cashAmount: Long, categoryId: Long?, legacy: Boolean): LedgerAccountEntity {
        if (legacy) return LedgerAccountEntity("clearing:legacy", "9999", "Legacy clearing", LedgerAccountKind.CLEARING)
        if (event.type == LedgerType.OPENING_BALANCE) {
            return LedgerAccountEntity("equity:opening", "3000", "Modal awal", LedgerAccountKind.EQUITY)
        }
        val income = event.type == LedgerType.INCOME || (event.type == LedgerType.AUTOMATION && cashAmount > 0) || (event.type == LedgerType.RESTORE_REVERSAL && cashAmount > 0)
        val expense = event.type in setOf(LedgerType.EXPENSE, LedgerType.UNEXPECTED_EXPENSE) ||
            (event.type == LedgerType.AUTOMATION && cashAmount < 0) || (event.type == LedgerType.RESTORE_REVERSAL && cashAmount < 0)
        return when {
            income && categoryId != null -> LedgerAccountEntity(
                "income:category:$categoryId", "4${categoryId.toString().padStart(6, '0')}",
                "Pemasukan kategori $categoryId", LedgerAccountKind.INCOME, categoryId = categoryId,
            )
            expense && categoryId != null -> LedgerAccountEntity(
                "expense:category:$categoryId", "5${categoryId.toString().padStart(6, '0')}",
                "Pengeluaran kategori $categoryId", LedgerAccountKind.EXPENSE, categoryId = categoryId,
            )
            income -> LedgerAccountEntity("income:general", "4000", "Pemasukan", LedgerAccountKind.INCOME)
            expense -> LedgerAccountEntity("expense:general", "5000", "Pengeluaran", LedgerAccountKind.EXPENSE)
            else -> LedgerAccountEntity("clearing:legacy", "9999", "Legacy clearing", LedgerAccountKind.CLEARING)
        }
    }

    private fun assetAccount(accountId: Long, name: String, channel: String) = LedgerAccountEntity(
        id = "asset:$accountId:$channel",
        code = "1${accountId.toString().padStart(6, '0')}${if (channel == FundingChannel.CASH) "01" else "02"}",
        name = "$name ${if (channel == FundingChannel.CASH) "Cash" else "eBudget"}",
        kind = LedgerAccountKind.ASSET,
        accountId = accountId,
        fundingChannel = channel,
    )

    private suspend fun assetLine(
        event: ActivityEventEntity,
        accountId: Long,
        channel: String,
        signedAmount: Long,
        legacy: Boolean = false,
    ): LedgerLineEntity {
        val account = requireNotNull(dao.accountById(accountId)) { "Akun aset tidak ditemukan" }
        val ledgerAccount = assetAccount(accountId, account.name, channel)
        dao.insertLedgerAccount(ledgerAccount)
        return LedgerLineEntity(
            eventId = event.id,
            ledgerAccountId = ledgerAccount.id,
            side = if (signedAmount > 0) LedgerSide.DEBIT else LedgerSide.CREDIT,
            amount = kotlin.math.abs(signedAmount),
            accountId = accountId,
            fundingChannel = channel,
            correlationId = event.relatedEventId,
            legacyBackfill = legacy,
        )
    }

    private fun List<LedgerLineEntity>.balancedWithClearing(event: ActivityEventEntity): List<LedgerLineEntity> {
        val debit = filter { it.side == LedgerSide.DEBIT }.sumOf { it.amount }
        val credit = filter { it.side == LedgerSide.CREDIT }.sumOf { it.amount }
        if (debit == credit) return this
        val difference = kotlin.math.abs(debit - credit)
        return this + LedgerLineEntity(
            eventId = event.id,
            ledgerAccountId = "clearing:legacy",
            side = if (debit < credit) LedgerSide.DEBIT else LedgerSide.CREDIT,
            amount = difference,
            accountId = event.accountId,
            correlationId = event.relatedEventId,
            legacyBackfill = true,
        )
    }

    private suspend fun validateEvent(eventId: String) {
        val ledger = dao.ledgerLinesForEvent(eventId)
        if (ledger.isNotEmpty()) {
            require(ledger.all { it.amount > 0 && it.side in setOf(LedgerSide.DEBIT, LedgerSide.CREDIT) }) {
                "Baris ledger tidak valid"
            }
            require(ledger.filter { it.side == LedgerSide.DEBIT }.sumOf { it.amount } ==
                ledger.filter { it.side == LedgerSide.CREDIT }.sumOf { it.amount }) {
                "Debit dan kredit event tidak seimbang"
            }
        }
        val budget = dao.budgetLinesForEvent(eventId)
        require(budget.isEmpty() || budget.sumOf { it.amount } == 0L) { "Subledger budget event tidak seimbang" }
        require(
            budget.groupBy { it.accountId to it.fundingChannel }
                .all { (_, lines) -> lines.sumOf { it.amount } == 0L },
        ) { "Subledger budget per akun dan kanal tidak seimbang" }
        val splits = dao.splitsForEvent(eventId)
        if (splits.isNotEmpty()) {
            val cashMagnitude = dao.cashLinesForEvent(eventId).sumOf { kotlin.math.abs(it.amount) }
            require(splits.sumOf { it.amount } == cashMagnitude) { "Total split tidak sama dengan nominal transaksi" }
        }
    }

    private suspend fun seal(event: ActivityEventEntity, keyId: String) {
        val latest = dao.latestSeal()
        val sequence = (latest?.sequence ?: 0L) + 1L
        val previous = latest?.chainHash ?: GENESIS_HASH
        val payloadHash = payloadHash(event)
        val chainHash = chainHash(previous, payloadHash, sequence)
        val actor = dao.actorProfile()?.displayName ?: "Pengguna lokal"
        val deviceId = dao.syncState()?.deviceId ?: "local-device"
        dao.insertJournalSeal(
            JournalSealEntity(
                eventId = event.id,
                sequence = sequence,
                previousChainHash = previous,
                payloadHash = payloadHash,
                chainHash = chainHash,
                signatureBase64 = signingKeys.sign(chainHash.toByteArray(StandardCharsets.UTF_8)),
                recordedAtUtc = event.createdAt,
                timezoneId = ZoneId.systemDefault().id,
                deviceId = deviceId,
                actor = actor,
                appVersion = BuildConfig.VERSION_NAME,
                keyId = keyId,
                legacyBackfill = event.createdAt < RELEASE_EPOCH_MILLIS,
            ),
        )
    }

    private suspend fun sealTeamEvent(event: ActivityEventEntity, keyId: String) {
        if (dao.teamEventProof(event.id) != null) return
        val account = requireNotNull(dao.accountById(event.accountId)) { "Akun event Team tidak ditemukan" }
        require(account.sharingMode == AccountSharingMode.TEAM && !account.teamId.isNullOrBlank()) {
            "Event bukan milik Team Account"
        }
        val workspace = requireNotNull(dao.teamWorkspace(event.accountId)) { "Workspace event Team tidak ditemukan" }
        require(workspace.teamId == account.teamId) { "Workspace event Team tidak cocok" }
        val deviceId = requireNotNull(dao.syncState()?.deviceId?.takeIf(String::isNotBlank)) {
            "Identitas perangkat Team tidak tersedia"
        }
        val actor = dao.actorProfile()?.displayName ?: "Pengguna lokal"
        val chainId = TeamLedgerCanonicalizer.chainId(workspace.teamId, deviceId)
        val latest = dao.latestTeamEventProof(chainId)
        val sequence = (latest?.sequence ?: 0L) + 1L
        val previous = latest?.chainHash ?: GENESIS_HASH
        val payloadHash = teamPayloadHash(event, workspace.teamId)
        val chainHash = TeamLedgerCanonicalizer.chainHash(
            teamId = workspace.teamId,
            chainId = chainId,
            previousChainHash = previous,
            payloadHash = payloadHash,
            sequence = sequence,
            recordedAtUtc = event.createdAt,
            deviceId = deviceId,
            actor = actor,
            appVersion = BuildConfig.VERSION_NAME,
            keyId = keyId,
        )
        dao.insertTeamEventProof(
            TeamEventProofEntity(
                eventId = event.id,
                teamId = workspace.teamId,
                chainId = chainId,
                sequence = sequence,
                previousChainHash = previous,
                payloadHash = payloadHash,
                chainHash = chainHash,
                signatureBase64 = signingKeys.sign(chainHash.toByteArray(StandardCharsets.UTF_8)),
                recordedAtUtc = event.createdAt,
                deviceId = deviceId,
                actor = actor,
                appVersion = BuildConfig.VERSION_NAME,
                keyId = keyId,
            ),
        )
    }

    private suspend fun teamPayloadHash(event: ActivityEventEntity, teamId: String): String =
        TeamLedgerCanonicalizer.payloadHash(
            event = event,
            teamId = teamId,
            cash = dao.cashLinesForEvent(event.id),
            budget = dao.budgetLinesForEvent(event.id),
            splits = dao.splitsForEvent(event.id),
            ledger = dao.ledgerLinesForEvent(event.id),
            audits = dao.auditsForEvent(event.id),
            receipts = dao.receiptsForEvent(event.id),
            allocationSyncIds = dao.allAllocations().associate { it.id to it.syncId },
            categorySyncIds = dao.allCategories().associate { it.id to it.syncId },
        )

    private suspend fun payloadHash(event: ActivityEventEntity): String = sha256(
        LedgerCanonicalizer.eventPayload(
            event,
            dao.cashLinesForEvent(event.id),
            dao.budgetLinesForEvent(event.id),
            dao.splitsForEvent(event.id),
            dao.ledgerLinesForEvent(event.id),
            dao.auditsForEvent(event.id),
            dao.receiptsForEvent(event.id),
        ).toByteArray(StandardCharsets.UTF_8),
    )

    private fun chainHash(previous: String, payload: String, sequence: Long): String =
        sha256("$previous:$payload:$sequence".toByteArray(StandardCharsets.UTF_8))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"
        private const val RELEASE_EPOCH_MILLIS = 1_784_678_400_000L
    }
}
