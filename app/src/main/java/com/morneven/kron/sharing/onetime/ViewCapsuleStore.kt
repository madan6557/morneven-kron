package com.morneven.kron.sharing.onetime

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ViewCapsuleStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val dir: File get() =
        File(context.noBackupFilesDir, "one-time-capsules").also { it.mkdirs() }

    fun save(capsule: ViewCapsule): Boolean = runCatching {
        val bytes = ViewCapsuleCodec.serializeCapsule(capsule)
        file(capsule.manifest.capsuleId).writeBytes(bytes)
        true
    }.getOrDefault(false)

    fun load(capsuleId: String): ViewCapsule? = runCatching {
        val bytes = file(capsuleId).readBytes()
        ViewCapsuleCodec.deserializeCapsule(bytes)
    }.getOrNull()

    fun delete(capsuleId: String) {
        file(capsuleId).delete()
    }

    fun listCapsuleIds(): List<String> {
        return dir.listFiles()?.map { it.nameWithoutExtension } ?: emptyList()
    }

    fun deleteAll() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private fun file(capsuleId: String): File = File(dir, "$capsuleId.cap")
}
