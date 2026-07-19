package com.morneven.kron.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqlCipherLibraryTest {
    @Test
    fun nativeLibraryLoadsIdempotentlyBeforeDatabaseAccess() {
        SqlCipherLibrary.ensureLoaded()
        SqlCipherLibrary.ensureLoaded()

        assertTrue(SqlCipherLibrary.isLoaded())
        SQLiteDatabase.create(null).use { database ->
            database.query("PRAGMA cipher_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertFalse(cursor.getString(0).isNullOrBlank())
            }
        }
    }
}
