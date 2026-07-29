package com.morneven.kron.ui

import com.morneven.kron.sync.DriveApiException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamFileAuthorizationTest {
    @Test
    fun onlyMissingOrDeniedTeamFileAccessReopensPicker() {
        assertTrue(teamFilePickerRequired(DriveApiException("redacted", 403, false)))
        assertTrue(teamFilePickerRequired(DriveApiException("redacted", 404, false)))
        assertFalse(teamFilePickerRequired(DriveApiException("redacted", 500, true)))
        assertFalse(teamFilePickerRequired(IllegalStateException()))
    }
}
