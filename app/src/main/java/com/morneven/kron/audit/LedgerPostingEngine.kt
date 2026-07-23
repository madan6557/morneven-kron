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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZoneId
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

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
                    accountId = dao.activeAccount()?.id ?: 0L,
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
    }

    suspend fun finalizeEvent(eventId: String) = database.withTransaction {
        if (dao.sealForEvent(eventId) != null) return@withTransaction
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
    }

    private suspend fun ensureBaseAccounts() {
        dao.insertLedgerAccounts(
            listOf(
                LedgerAccountEntity("income:general", "4000", "Pemasukan", LedgerAccountKind.INCOME),
                LedgerAccountEntity("expense:general", "5000", "Pengeluaran", LedgerAccountKind.EXPENSE),
                LedgerAccountEntity("equity:opening", "3000", "Modal awal", LedgerAccountKind.EQUITY),
                LedgerAccountEntity("clearing:legacy", "9999", "Legacy clearing", LedgerAccountKind.CLEARING),
                LedgerAccountEntity("clearing:budget", "9998", "Budget clearing", LedgerAccountKind.CLEARING),
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
        if (cash.isEmpty()) {
            val budget = dao.budgetLinesForEvent(event.id).filter { it.amount != 0L }
            if (budget.isNotEmpty()) {
                val lines = budget.map { line ->
                    LedgerLineEntity(
                        eventId = event.id,
                        ledgerAccountId = "clearing:budget",
                        side = if (line.amount > 0) LedgerSide.DEBIT else LedgerSide.CREDIT,
                        amount = safeAbs(line.amount),
                        accountId = line.accountId,
                        fundingChannel = line.fundingChannel,
                        categoryId = null,
                        correlationId = event.relatedEventId,
                        legacyBackfill = false,
                    )
                }
                dao.insertLedgerLines(lines)
            }
            return
        }
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
                val expected = safeAbs(cashLine.amount)
                val matchingSplits = splits.filter { it.amount > 0 }
                if (matchingSplits.isNotEmpty() && safeSumOf(matchingSplits.map { it.amount }) == expected) {
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

    private suspend fun counterpartAccount(event: ActivityEventEntity, cashAmount: Long, categoryId: Long?, legacy: Boolean): LedgerAccountEntity {
        if (legacy) return LedgerAccountEntity("clearing:legacy", "9999", "Legacy clearing", LedgerAccountKind.CLEARING)
        if (event.type == LedgerType.OPENING_BALANCE) {
            return LedgerAccountEntity("equity:opening", "3000", "Modal awal", LedgerAccountKind.EQUITY)
        }
        if (event.type == LedgerType.RESTORE_REVERSAL) {
            val originalId = event.relatedEventId
            val originalEvent = originalId?.let { runCatching { dao.eventById(it) }.getOrNull() }
            val originalType = originalEvent?.type
            val income = originalType == LedgerType.INCOME
            val expense = originalType in setOf(LedgerType.EXPENSE, LedgerType.UNEXPECTED_EXPENSE)
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
        val income = event.type == LedgerType.INCOME || (event.type == LedgerType.AUTOMATION && cashAmount > 0)
        val expense = event.type in setOf(LedgerType.EXPENSE, LedgerType.UNEXPECTED_EXPENSE) ||
            (event.type == LedgerType.AUTOMATION && cashAmount < 0)
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
            amount = safeAbs(signedAmount),
            accountId = accountId,
            fundingChannel = channel,
            correlationId = event.relatedEventId,
            legacyBackfill = legacy,
        )
    }

    private fun List<LedgerLineEntity>.balancedWithClearing(event: ActivityEventEntity): List<LedgerLineEntity> {
        val debit = safeSumOf(filter { it.side == LedgerSide.DEBIT }.map { it.amount })
        val credit = safeSumOf(filter { it.side == LedgerSide.CREDIT }.map { it.amount })
        if (debit == credit) return this
        val difference = safeAbs(debit - credit)
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
            require(safeSumOf(ledger.filter { it.side == LedgerSide.DEBIT }.map { it.amount }) ==
                safeSumOf(ledger.filter { it.side == LedgerSide.CREDIT }.map { it.amount })) {
                "Debit dan kredit event tidak seimbang"
            }
        }
        val budget = dao.budgetLinesForEvent(eventId)
        require(budget.isEmpty() || safeSumOf(budget.map { it.amount }) == 0L) { "Subledger budget event tidak seimbang" }
        require(
            budget.groupBy { it.accountId to it.fundingChannel }
                .all { (_, lines) -> safeSumOf(lines.map { it.amount }) == 0L },
        ) { "Subledger budget per akun dan kanal tidak seimbang" }
        val splits = dao.splitsForEvent(eventId)
        if (splits.isNotEmpty()) {
            val cashMagnitude = dao.cashLinesForEvent(eventId).sumOf { safeAbs(it.amount) }
            require(safeSumOf(splits.map { it.amount }) == cashMagnitude) { "Total split tidak sama dengan nominal transaksi" }
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

        fun safeAbs(value: Long): Long {
            if (value == Long.MIN_VALUE) throw ArithmeticException("Long overflow on absolute value")
            return abs(value)
        }

        fun safeAdd(a: Long, b: Long): Long {
            val result = a + b
            if ((a xor result) and (b xor result) < 0) throw ArithmeticException("Long overflow on addition")
            return result
        }

        fun safeSumOf(values: Sequence<Long>): Long {
            var sum = 0L
            values.forEach { sum = safeAdd(sum, it) }
            return sum
        }

        fun safeSumOf(values: Iterable<Long>): Long = safeSumOf(values.asSequence())
    }
}
