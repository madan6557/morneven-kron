package com.morneven.kron.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrivePickerSelectionTest {
    @Test
    fun acceptsOnlyTheExpectedSingleFolder() {
        assertTrue(pickedDriveFolderMatches(" folder-1 ", "folder-1"))
        assertFalse(pickedDriveFolderMatches(null, "folder-1"))
        assertFalse(pickedDriveFolderMatches("folder-2", "folder-1"))
        assertFalse(pickedDriveFolderMatches("folder-1,folder-2", "folder-1"))
    }
}
