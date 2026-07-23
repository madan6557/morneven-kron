package com.morneven.kron.evidence

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.morneven.kron.BuildConfig
import com.morneven.kron.audit.EvidenceSigningKeyManager
import com.morneven.kron.audit.LedgerCanonicalizer
import com.morneven.kron.audit.LedgerPostingEngine
import com.morneven.kron.data.ActivityEventEntity
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.LedgerSide
import com.morneven.kron.security.EncryptedAttachmentStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.Locale
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

data class EvidenceVerificationResult(
    val valid: Boolean,
    val eventCount: Int,
    val attachmentCount: Int,
    val chainHead: String,
    val message: String,
)

data class EvidenceHealth(
    val valid: Boolean,
    val eventCount: Long,
    val sealCount: Long,
    val missingEvidence: Int,
    val changedEvidence: Int,
    val legacyEvidence: Int,
    val message: String,
)

@Singleton
class EvidencePackageManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: KronDatabase,
    private val attachmentStore: EncryptedAttachmentStore,
    private val postingEngine: LedgerPostingEngine,
    private val signingKeys: EvidenceSigningKeyManager,
) {
    private val dao get() = database.kronDao()

    suspend fun health(): EvidenceHealth = withContext(Dispatchers.IO) {
        runCatching {
            postingEngine.validateAll()
            var missing = 0
            var changed = 0
            var legacy = 0
            dao.allReceipts().forEach { receipt ->
                if (receipt.origin == "LEGACY") legacy++
                val file = File(receipt.localPath)
                if (!file.isFile) {
                    missing++
                } else {
                    val inspected = runCatching { attachmentStore.inspect(file) }.getOrNull()
                    if (inspected == null || inspected.sha256 != receipt.sha256 || inspected.byteSize != receipt.byteSize) changed++
                }
            }
            EvidenceHealth(
                valid = missing == 0 && changed == 0,
                eventCount = dao.eventCount(),
                sealCount = dao.sealCount(),
                missingEvidence = missing,
                changedEvidence = changed,
                legacyEvidence = legacy,
                message = if (missing == 0 && changed == 0) "Ledger dan bukti dapat diverifikasi" else "Ada bukti yang perlu diperiksa",
            )
        }.getOrElse { error ->
            EvidenceHealth(false, dao.eventCount(), dao.sealCount(), 0, 0, 0, error.message ?: "Pemeriksaan gagal")
        }
    }

    suspend fun exportPackage(uri: Uri, startDay: Long, endDay: Long, passphrase: CharArray) = withContext(Dispatchers.IO) {
        require(passphrase.size >= MIN_PASSPHRASE) { "Passphrase minimal 12 karakter" }
        require(startDay <= endDay) { "Rentang tanggal tidak valid" }
        postingEngine.validateAll()
        exportSelection(uri, dao.eventsBetween(startDay, endDay), startDay, endDay, passphrase)
    }

    suspend fun exportEventPackage(uri: Uri, eventId: String, passphrase: CharArray) = withContext(Dispatchers.IO) {
        require(passphrase.size >= MIN_PASSPHRASE) { "Passphrase minimal 12 karakter" }
        postingEngine.validateAll()
        val event = requireNotNull(dao.eventById(eventId)) { "Event tidak ditemukan" }
        exportSelection(uri, listOf(event), event.effectiveEpochDay, event.effectiveEpochDay, passphrase)
    }

    private suspend fun exportSelection(
        uri: Uri,
        events: List<ActivityEventEntity>,
        startDay: Long,
        endDay: Long,
        passphrase: CharArray,
    ) {
        val stagingDirectory = File(context.cacheDir, "evidence-export").apply { mkdirs() }
        val zipFile = File.createTempFile("kron-evidence-", ".zip", stagingDirectory)
        val encryptedFile = File.createTempFile("kron-evidence-", ".bin", stagingDirectory)
        try {
            buildZip(zipFile, events, startDay, endDay)
            encrypt(zipFile, encryptedFile, passphrase)
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                encryptedFile.inputStream().buffered().use { it.copyTo(output) }
            } ?: error("Tujuan paket bukti tidak dapat dibuka")
        } finally {
            passphrase.fill('\u0000')
            zipFile.delete()
            encryptedFile.delete()
        }
    }

    suspend fun verifyPackage(uri: Uri, passphrase: CharArray): EvidenceVerificationResult = withContext(Dispatchers.IO) {
        require(passphrase.size >= MIN_PASSPHRASE) { "Passphrase minimal 12 karakter" }
        val stagingDirectory = File(context.cacheDir, "evidence-verify").apply { mkdirs() }
        val encryptedFile = File.createTempFile("kron-evidence-input-", ".bin", stagingDirectory)
        val zipFile = File.createTempFile("kron-evidence-plain-", ".zip", stagingDirectory)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                encryptedFile.outputStream().buffered().use { output -> input.copyToWithLimit(output, MAX_PACKAGE_BYTES) }
            } ?: error("Paket bukti tidak dapat dibuka")
            decrypt(encryptedFile, zipFile, passphrase)
            verifyZip(zipFile)
        } finally {
            passphrase.fill('\u0000')
            encryptedFile.delete()
            zipFile.delete()
        }
    }

    suspend fun exportPdf(uri: Uri, startDay: Long, endDay: Long) = withContext(Dispatchers.IO) {
        require(startDay <= endDay) { "Rentang tanggal tidak valid" }
        postingEngine.validateAll()
        val events = dao.eventsBetween(startDay, endDay)
        val receiptsByEvent = dao.allReceipts().groupBy { it.eventId }
        val allEventsById = dao.allEvents().associateBy { it.id }
        val allCashLines = dao.allCashLines()
        val accounts = dao.allAccounts().associateBy { it.id }
        val openingBalances = allCashLines.filter { line ->
            (allEventsById[line.eventId]?.effectiveEpochDay ?: Long.MAX_VALUE) < startDay
        }.groupBy { it.accountId to it.fundingChannel }.mapValues { (_, lines) -> lines.sumOf { it.amount } }
        val closingBalances = allCashLines.filter { line ->
            (allEventsById[line.eventId]?.effectiveEpochDay ?: Long.MAX_VALUE) <= endDay
        }.groupBy { it.accountId to it.fundingChannel }.mapValues { (_, lines) -> lines.sumOf { it.amount } }
        val selectedCash = events.flatMap { event -> dao.cashLinesForEvent(event.id).map { event to it } }
        val income = selectedCash.filter { (event, line) ->
            line.amount > 0 && event.type in setOf("INCOME", "OPENING_BALANCE", "AUTOMATION")
        }.sumOf { it.second.amount }
        val expense = selectedCash.filter { (event, line) ->
            line.amount < 0 && event.type in setOf("EXPENSE", "UNEXPECTED_EXPENSE", "AUTOMATION")
        }.sumOf { -it.second.amount }
        val reversalCount = events.count { it.type == "REVERSAL" }
        val netMovement = selectedCash.sumOf { it.second.amount }
        val ledgerDigestPayload = buildString {
            events.forEachIndexed { index, event ->
                if (index > 0) append('\n')
                append(canonicalPayload(event))
            }
        }
        val ledgerDigest = sha256(ledgerDigestPayload.toByteArray(StandardCharsets.UTF_8))
        val pdf = PdfDocument()
        val titlePaint = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val headingPaint = Paint().apply { textSize = 12f; isFakeBoldText = true }
        val textPaint = Paint().apply { textSize = 9f }
        val mutedPaint = Paint().apply { textSize = 8f; color = 0xFF555555.toInt() }
        var pageNumber = 0
        var page: PdfDocument.Page? = null
        var y = 0f

        fun newPage() {
            page?.let(pdf::finishPage)
            pageNumber++
            page = pdf.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNumber).create())
            y = 42f
        }

        fun line(text: String, paint: Paint = textPaint, gap: Float = 14f) {
            wrapText(text, MAX_PDF_LINE_CHARS).forEach { segment ->
                if (page == null || y > 800f) newPage()
                page!!.canvas.drawText(segment, 36f, y, paint)
                y += gap
            }
        }

        newPage()
        line("KRON ${BuildConfig.VERSION_NAME} - Paket Pertanggungjawaban", titlePaint, 26f)
        line("Basis pencatatan: kas", headingPaint, 18f)
        line("Rentang: ${LocalDate.ofEpochDay(startDay)} sampai ${LocalDate.ofEpochDay(endDay)}")
        line("Dibuat: ${Instant.now()}")
        line("Actor: ${dao.actorProfile()?.displayName ?: "Pengguna lokal"}")
        line("Jumlah event: ${events.size}")
        line("Chain head: ${dao.latestSeal()?.chainHash ?: LedgerPostingEngine.GENESIS_HASH}", mutedPaint, 18f)
        line("Hash ledger rentang: $ledgerDigest", mutedPaint, 18f)
        line("Ringkasan basis kas", headingPaint, 18f)
        line("Pemasukan tercatat: ${money(income)} | Pengeluaran tercatat: ${money(expense)}")
        line("Perubahan kas bersih termasuk reversal: ${money(netMovement)}")
        line("Reversal dalam rentang: $reversalCount")
        line("Saldo akun", headingPaint, 18f)
        (openingBalances.keys + closingBalances.keys).distinct().sortedWith(compareBy({ it.first }, { it.second })).forEach { key ->
            line(
                "${accounts[key.first]?.name ?: "Akun ${key.first}"} ${key.second}: " +
                    "awal ${money(openingBalances[key] ?: 0L)}, akhir ${money(closingBalances[key] ?: 0L)}",
            )
        }
        line("Ringkasan transaksi", headingPaint, 18f)
        events.forEach { event ->
            val ledger = dao.ledgerLinesForEvent(event.id)
            val debit = ledger.filter { it.side == LedgerSide.DEBIT }.sumOf { it.amount }
            val credit = ledger.filter { it.side == LedgerSide.CREDIT }.sumOf { it.amount }
            line("${LocalDate.ofEpochDay(event.effectiveEpochDay)} | ${event.type} | ${event.title}", headingPaint)
            line("Event ${event.id} | Debit ${money(debit)} | Kredit ${money(credit)}", textPaint)
            if (event.note.isNotBlank()) line("Catatan: ${event.note}", mutedPaint)
            val attachments = receiptsByEvent[event.id].orEmpty()
            if (attachments.isNotEmpty()) line("Bukti: ${attachments.joinToString { it.sha256.take(12) }}", mutedPaint)
            y += 4f
        }
        y += 8f
        line("Dokumen pendukung yang direkonstruksi dari jurnal KRON. Bukan laporan audit independen atau tanda tangan elektronik tersertifikasi.", mutedPaint)
        page?.let(pdf::finishPage)
        context.contentResolver.openOutputStream(uri, "w")?.use(pdf::writeTo)
            ?: error("Tujuan PDF tidak dapat dibuka")
        pdf.close()
    }

    private suspend fun buildZip(target: File, events: List<ActivityEventEntity>, startDay: Long, endDay: Long) {
        val eventIds = events.mapTo(hashSetOf()) { it.id }
        val receipts = dao.allReceipts().filter { it.eventId in eventIds || it.evidenceEventId in eventIds }
        val files = linkedMapOf<String, ByteArray>()
        files["ledger.jsonl"] = buildString {
            events.forEach { event -> append(canonicalPayload(event)).append('\n') }
        }.toByteArray(StandardCharsets.UTF_8)
        val allSeals = dao.allJournalSeals()
        val allKeys = dao.allEvidenceKeys()
        files["seals.jsonl"] = allSeals
            .joinToString("\n") { seal ->
                JSONObject()
                    .put("eventId", seal.eventId)
                    .put("sequence", seal.sequence.toString())
                    .put("previousChainHash", seal.previousChainHash)
                    .put("payloadHash", seal.payloadHash)
                    .put("chainHash", seal.chainHash)
                    .put("signature", seal.signatureBase64)
                    .put("keyId", seal.keyId)
                    .put("recordedAtUtc", seal.recordedAtUtc.toString())
                    .put("timezoneId", seal.timezoneId)
                    .put("deviceId", seal.deviceId)
                    .put("actor", seal.actor)
                    .put("appVersion", seal.appVersion)
                    .put("legacyBackfill", seal.legacyBackfill)
                    .toString()
            }.plus(if (allSeals.isEmpty()) "" else "\n").toByteArray(StandardCharsets.UTF_8)
        files["keys.jsonl"] = allKeys.joinToString("\n") { key ->
            JSONObject()
                .put("id", key.id)
                .put("algorithm", key.algorithm)
                .put("publicKey", key.publicKeyBase64)
                .put("certificate", key.certificateBase64)
                .put("fingerprint", key.fingerprint)
                .put("securityLevel", key.securityLevel)
                .put("createdAt", key.createdAt.toString())
                .put("retiredAt", key.retiredAt?.toString())
                .toString()
        }.plus(if (allKeys.isEmpty()) "" else "\n").toByteArray(StandardCharsets.UTF_8)
        files["audit.jsonl"] = events.flatMap { event -> dao.auditsForEvent(event.id) }
            .joinToString("\n") { audit ->
                JSONObject().put("eventId", audit.eventId).put("reason", audit.reason)
                    .put("before", audit.beforeJson).put("after", audit.afterJson).toString()
            }.plus(if (events.isEmpty()) "" else "\n").toByteArray(StandardCharsets.UTF_8)

        val attachmentEntries = linkedMapOf<String, File>()
        receipts.forEach { receipt ->
            val encrypted = File(receipt.localPath)
            require(encrypted.isFile) { "Bukti ${receipt.id} tidak ditemukan" }
            val inspected = attachmentStore.inspect(encrypted)
            require(inspected.sha256 == receipt.sha256 && inspected.byteSize == receipt.byteSize) { "Bukti ${receipt.id} berubah" }
            attachmentEntries["evidence/${receipt.id}-${safeFileName(receipt.displayName)}"] = encrypted
        }

        val fileHashes = linkedMapOf<String, String>()
        files.forEach { (path, bytes) -> fileHashes[path] = sha256(bytes) }
        attachmentEntries.forEach { (path, encrypted) ->
            val digest = MessageDigest.getInstance("SHA-256")
            attachmentStore.decrypt(encrypted, object : java.io.OutputStream() {
                override fun write(value: Int) { digest.update(value.toByte()) }
                override fun write(buffer: ByteArray, offset: Int, length: Int) { digest.update(buffer, offset, length) }
            })
            fileHashes[path] = digest.digest().toHex()
        }
        val latest = dao.latestSeal()
        val selectedSeal = events.mapNotNull { dao.sealForEvent(it.id) }.maxByOrNull { it.sequence }
        val manifest = JSONObject()
            .put("format", "KRON_EVIDENCE")
            .put("formatVersion", 1)
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("basis", "CASH")
            .put("startEpochDay", startDay.toString())
            .put("endEpochDay", endDay.toString())
            .put("generatedAtUtc", System.currentTimeMillis().toString())
            .put("eventCount", events.size)
            .put("attachmentCount", receipts.size)
            .put("chainHead", latest?.chainHash ?: LedgerPostingEngine.GENESIS_HASH)
            .put("rangeChainHead", selectedSeal?.chainHash ?: LedgerPostingEngine.GENESIS_HASH)
            .put("files", JSONObject(fileHashes as Map<*, *>))
            .put("disclaimer", DISCLAIMER)
        val key = signingKeys.publicRecord()
        manifest.put("manifestKeyId", key.id)
        val finalManifestBytes = manifest.toString().toByteArray(StandardCharsets.UTF_8)
        val finalManifestHash = sha256(finalManifestBytes)
        val manifestSignature = signingKeys.sign(finalManifestHash.toByteArray(StandardCharsets.UTF_8))

        ZipOutputStream(BufferedOutputStream(FileOutputStream(target))).use { zip ->
            putBytes(zip, "manifest.json", finalManifestBytes)
            putBytes(zip, "manifest.sha256", finalManifestHash.toByteArray(StandardCharsets.US_ASCII))
            putBytes(zip, "manifest.sig", manifestSignature.toByteArray(StandardCharsets.US_ASCII))
            putBytes(zip, "certificate.cer", Base64.getDecoder().decode(key.certificateBase64))
            files.forEach { (path, bytes) -> putBytes(zip, path, bytes) }
            attachmentEntries.forEach { (path, encrypted) ->
                zip.putNextEntry(ZipEntry(path))
                attachmentStore.decrypt(encrypted, zip)
                zip.closeEntry()
            }
        }
        FileOutputStream(target, true).use { it.fd.sync() }
    }

    private suspend fun canonicalPayload(event: ActivityEventEntity): String = LedgerCanonicalizer.eventPayload(
        event,
        dao.cashLinesForEvent(event.id),
        dao.budgetLinesForEvent(event.id),
        dao.splitsForEvent(event.id),
        dao.ledgerLinesForEvent(event.id),
        dao.auditsForEvent(event.id),
        dao.receiptsForEvent(event.id),
    )

    private fun encrypt(source: File, target: File, passphrase: CharArray) {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = com.morneven.kron.security.generateNonce(NONCE_BYTES)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance(AES_GCM).apply {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(MAGIC)
        }
        DataOutputStream(BufferedOutputStream(FileOutputStream(target))).use { output ->
            output.write(MAGIC)
            output.writeInt(PBKDF2_ITERATIONS)
            output.write(salt)
            output.write(nonce)
            CipherOutputStream(output, cipher).use { encrypted -> source.inputStream().buffered().use { it.copyTo(encrypted) } }
        }
        FileOutputStream(target, true).use { it.fd.sync() }
    }

    private fun decrypt(source: File, target: File, passphrase: CharArray) {
        DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Format paket bukti tidak dikenali" }
            val iterations = input.readInt()
            require(iterations == PBKDF2_ITERATIONS) { "Parameter keamanan paket tidak didukung" }
            val salt = ByteArray(SALT_BYTES).also(input::readFully)
            val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
            val cipher = Cipher.getInstance(AES_GCM).apply {
                init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(MAGIC)
            }
            FileOutputStream(target).buffered().use { output ->
                CipherInputStream(input, cipher).use { it.copyToWithLimit(output, MAX_PACKAGE_BYTES) }
            }
        }
    }

    private fun verifyZip(source: File): EvidenceVerificationResult {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(BufferedInputStream(FileInputStream(source))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "Direktori tidak diizinkan dalam paket bukti" }
                require(isSafeEntry(entry.name)) { "Path paket bukti tidak aman" }
                require(entry.name !in entries) { "Nama file paket bukti ganda" }
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_PACKAGE_BYTES) { "Paket bukti terlalu besar" }
                    require(output.size() + read <= MAX_ENTRY_BYTES) { "File dalam paket bukti terlalu besar" }
                    output.write(buffer, 0, read)
                }
                entries[entry.name] = output.toByteArray()
                zip.closeEntry()
            }
        }
        val manifestBytes = requireNotNull(entries["manifest.json"]) { "Manifest tidak ditemukan" }
        val expectedManifestHash = requireNotNull(entries["manifest.sha256"]).toString(StandardCharsets.US_ASCII)
        require(sha256(manifestBytes) == expectedManifestHash) { "Checksum manifest tidak cocok" }
        val signature = requireNotNull(entries["manifest.sig"]).toString(StandardCharsets.US_ASCII)
        val certificateBytes = requireNotNull(entries["certificate.cer"])
        val certificate = CertificateFactory.getInstance("X.509").generateCertificate(certificateBytes.inputStream())
        val certificateBase64 = Base64.getEncoder().encodeToString(certificate.encoded)
        require(signingKeys.verify(expectedManifestHash.toByteArray(StandardCharsets.UTF_8), signature, certificateBase64)) {
            "Tanda tangan manifest tidak valid"
        }
        val manifest = JSONObject(manifestBytes.toString(StandardCharsets.UTF_8))
        require(manifest.getString("format") == "KRON_EVIDENCE" && manifest.getInt("formatVersion") == 1) {
            "Versi paket bukti tidak didukung"
        }
        val files = manifest.getJSONObject("files")
        files.keys().forEach { path ->
            val bytes = requireNotNull(entries[path]) { "File $path tidak ditemukan" }
            require(sha256(bytes) == files.getString(path)) { "Checksum $path tidak cocok" }
        }
        val allowedEntries = files.keys().asSequence().toSet() + setOf(
            "manifest.json", "manifest.sha256", "manifest.sig", "certificate.cer",
        )
        require(entries.keys.all { it in allowedEntries }) { "Paket bukti memiliki file yang tidak terdaftar" }

        val keyCertificates = requireNotNull(entries["keys.jsonl"]).toString(StandardCharsets.UTF_8)
            .lineSequence().filter(String::isNotBlank).associate { line ->
                val value = JSONObject(line)
                value.getString("id") to value.getString("certificate")
            }
        require(manifest.getString("manifestKeyId") in keyCertificates) { "Kunci manifest tidak terdaftar" }
        require(keyCertificates[manifest.getString("manifestKeyId")] == certificateBase64) {
            "Sertifikat penanda tangan manifest tidak cocok"
        }
        val seals = requireNotNull(entries["seals.jsonl"]).toString(StandardCharsets.UTF_8)
            .lineSequence().filter(String::isNotBlank).map(::JSONObject).toList()
        var previous = LedgerPostingEngine.GENESIS_HASH
        seals.forEachIndexed { index, seal ->
            val sequence = seal.getString("sequence").toLong()
            require(sequence == index + 1L) { "Urutan seal paket tidak valid" }
            require(seal.getString("previousChainHash") == previous) { "Rantai seal paket terputus" }
            val expected = sha256("$previous:${seal.getString("payloadHash")}:$sequence".toByteArray(StandardCharsets.UTF_8))
            require(seal.getString("chainHash") == expected) { "Hash rantai paket tidak valid" }
            val keyCertificate = requireNotNull(keyCertificates[seal.getString("keyId")]) { "Sertifikat seal tidak ditemukan" }
            require(signingKeys.verify(expected.toByteArray(StandardCharsets.UTF_8), seal.getString("signature"), keyCertificate)) {
                "Tanda tangan seal paket tidak valid"
            }
            previous = expected
        }
        require(previous == manifest.getString("chainHead")) { "Chain head paket tidak cocok" }

        val sealPayloads = seals.associate { it.getString("eventId") to it.getString("payloadHash") }
        val ledgerPayloads = requireNotNull(entries["ledger.jsonl"]).toString(StandardCharsets.UTF_8)
            .lineSequence().filter(String::isNotBlank).toList()
        ledgerPayloads.forEach { payload ->
            val eventId = JSONObject(payload).getString("eventId")
            require(sealPayloads[eventId] == sha256(payload.toByteArray(StandardCharsets.UTF_8))) {
                "Payload event $eventId tidak cocok dengan seal"
            }
        }
        require(ledgerPayloads.size == manifest.getInt("eventCount")) { "Jumlah event paket tidak cocok" }
        require(entries.keys.count { it.startsWith("evidence/") } == manifest.getInt("attachmentCount")) {
            "Jumlah bukti paket tidak cocok"
        }
        return EvidenceVerificationResult(
            valid = true,
            eventCount = manifest.getInt("eventCount"),
            attachmentCount = manifest.getInt("attachmentCount"),
            chainHead = manifest.getString("chainHead"),
            message = "Paket bukti valid dan tidak berubah",
        )
    }

    private fun putBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun isSafeEntry(name: String): Boolean = name.isNotBlank() && !name.startsWith('/') && !name.startsWith('\\') &&
        !name.contains("..") && !name.contains(':') && name.split('/').none { it.isBlank() || it == "." || it == ".." }

    private fun safeFileName(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "evidence.bin" }

    private fun money(value: Long): String = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("id-ID")).apply {
        maximumFractionDigits = 0
        minimumFractionDigits = 0
    }.format(value)

    private fun wrapText(value: String, limit: Int): List<String> {
        if (value.length <= limit) return listOf(value)
        val result = mutableListOf<String>()
        var remaining = value
        while (remaining.length > limit) {
            val split = remaining.lastIndexOf(' ', startIndex = limit).takeIf { it > 0 } ?: limit
            result += remaining.substring(0, split)
            remaining = remaining.substring(split).trimStart()
        }
        if (remaining.isNotEmpty()) result += remaining
        return result
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun java.io.InputStream.copyToWithLimit(output: java.io.OutputStream, limit: Long) {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            total += read
            require(total <= limit) { "Paket bukti terlalu besar" }
            output.write(buffer, 0, read)
        }
    }

    companion object {
        const val DISCLAIMER = "Dokumen pendukung yang direkonstruksi dari jurnal KRON. Bukan laporan audit independen atau tanda tangan elektronik tersertifikasi."
        private const val MIN_PASSPHRASE = 12
        private const val PBKDF2_ITERATIONS = 600_000
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val MAX_PACKAGE_BYTES = 512L * 1024 * 1024
        private const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
        private const val MAX_PDF_LINE_CHARS = 92
        private val MAGIC = "KRONEVD1".toByteArray(StandardCharsets.US_ASCII)
    }
}
