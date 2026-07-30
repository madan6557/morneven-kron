package com.morneven.kron.ui

import com.morneven.kron.sync.DriveApiException
import com.morneven.kron.team.teamSnapshotMissing
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamFileAuthorizationTest {
    @Test
    fun onlyDeniedTeamFileAccessReopensPicker() {
        assertTrue(teamFilePickerRequired(DriveApiException("redacted", 403, false)))
        assertFalse(teamFilePickerRequired(DriveApiException("redacted", 404, false)))
        assertTrue(teamSnapshotMissing(DriveApiException("redacted", 404, false)))
        assertFalse(teamFilePickerRequired(DriveApiException("redacted", 500, true)))
        assertFalse(teamFilePickerRequired(IllegalStateException()))
    }
}
