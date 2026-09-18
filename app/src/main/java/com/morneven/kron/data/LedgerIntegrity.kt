package com.morneven.kron.data

/**
 * One named condition a healthy KRON ledger must satisfy.
 *
 * [left] and [right] are the two figures that were compared, kept as raw amounts so the UI can
 * respect the value visibility setting instead of receiving money already baked into a string.
 */
data class LedgerCheck(
    val label: String,
    val passed: Boolean,
    val leftLabel: String? = null,
    val left: Long? = null,
    val rightLabel: String? = null,
    val right: Long? = null,
    val note: String? = null,
)

/** Per channel figures for one scope, used to compare real balance against earmarked funds. */
data class ChannelIntegrityRow(
    val fundingChannel: String,
    val cash: Long,
    val available: Long,
)

/** Per account figures, including the same comparison split by channel. */
data class AccountIntegrityRow(
    val name: String,
    val cash: Long,
    val available: Long,
    val channels: List<ChannelIntegrityRow>,
)

/**
 * Everything a ledger self check needs, gathered with read only queries.
 *
 * Separating the gathering from the judgement keeps the judgement pure, so the rules that decide
 * whether a ledger is healthy can be tested without a database.
 */
data class LedgerIntegrityInput(
    val unbalancedLedgerEventCount: Int = 0,
    val unbalancedBudgetEventId: String? = null,
    val accountCount: Int = 0,
    val activeAccountCount: Int = 0,
    val totalCash: Long = 0,
    val totalAvailable: Long = 0,
    val channels: List<ChannelIntegrityRow> = emptyList(),
    val accounts: List<AccountIntegrityRow> = emptyList(),
    val eventCount: Long = 0,
    val unsealedEventCount: Long = 0,
)

/**
 * Outcome of a read only ledger self check.
 *
 * This reports the same conditions that `assertInvariant` enforces on every write, but it collects
 * every result instead of stopping at the first failure. An enforcement path that can only throw
 * tells the user something is wrong; this tells them which relationship broke and by how much.
 */
data class LedgerIntegrityReport(
    val checks: List<LedgerCheck> = emptyList(),
    val eventCount: Long = 0,
    val unsealedEventCount: Long = 0,
    val checkedAtEpochMillis: Long = 0,
) {
    val failed: List<LedgerCheck> get() = checks.filter { !it.passed }
    val healthy: Boolean get() = checks.isNotEmpty() && failed.isEmpty()
}

private fun channelLabel(channel: String) = if (channel == FundingChannel.CASH) "Cash" else "eBudget"

fun LedgerIntegrityInput.toReport(checkedAtEpochMillis: Long): LedgerIntegrityReport {
    val checks = buildList {
        add(
            LedgerCheck(
                label = "General ledger seimbang",
                passed = unbalancedLedgerEventCount == 0,
                note = if (unbalancedLedgerEventCount == 0) {
                    null
                } else {
                    "$unbalancedLedgerEventCount event memiliki debit dan kredit yang tidak sama"
                },
            ),
        )
        add(
            LedgerCheck(
                label = "Tepat satu akun aktif",
                passed = accountCount == 0 || activeAccountCount == 1,
                note = if (accountCount == 0) "Belum ada akun" else "$activeAccountCount akun aktif",
            ),
        )
        add(
            LedgerCheck(
                label = "Total kas sama dengan total dana",
                passed = totalCash == totalAvailable,
                leftLabel = "Kas",
                left = totalCash,
                rightLabel = "Dana",
                right = totalAvailable,
            ),
        )
        channels.forEach { channel ->
            add(
                LedgerCheck(
                    label = "Kanal ${channelLabel(channel.fundingChannel)} seimbang",
                    passed = channel.cash == channel.available,
                    leftLabel = "Kas",
                    left = channel.cash,
                    rightLabel = "Dana",
                    right = channel.available,
                ),
            )
        }
        accounts.forEach { account ->
            add(
                LedgerCheck(
                    label = "Akun ${account.name} seimbang",
                    passed = account.cash == account.available,
                    leftLabel = "Kas",
                    left = account.cash,
                    rightLabel = "Dana",
                    right = account.available,
                ),
            )
            account.channels.forEach { channel ->
                add(
                    LedgerCheck(
                        label = "Akun ${account.name} kanal ${channelLabel(channel.fundingChannel)} seimbang",
                        passed = channel.cash == channel.available,
                        leftLabel = "Kas",
                        left = channel.cash,
                        rightLabel = "Dana",
                        right = channel.available,
                    ),
                )
            }
        }
        add(
            LedgerCheck(
                label = "Setiap event budget seimbang",
                passed = unbalancedBudgetEventId == null,
                note = unbalancedBudgetEventId?.let { "Event $it tidak seimbang" },
            ),
        )
        add(
            LedgerCheck(
                label = "Seluruh event tersegel",
                passed = unsealedEventCount == 0L,
                note = if (unsealedEventCount == 0L) {
                    "$eventCount event tersegel"
                } else {
                    "$unsealedEventCount event belum tersegel"
                },
            ),
        )
    }
    return LedgerIntegrityReport(
        checks = checks,
        eventCount = eventCount,
        unsealedEventCount = unsealedEventCount,
        checkedAtEpochMillis = checkedAtEpochMillis,
    )
}
