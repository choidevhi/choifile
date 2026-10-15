package com.choidev.choifile

import com.choidev.choifile.ebook.opensAsBook
import com.choidev.choifile.security.TxtOpen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V061Test {
    @Test fun askModeLeavesTheChoiceToTheUser() {
        assertNull(opensAsBook(TxtOpen.ASK))
    }

    @Test fun pinnedModesOpenWithoutAsking() {
        assertEquals(false, opensAsBook(TxtOpen.TEXT))
        assertEquals(true, opensAsBook(TxtOpen.BOOK))
    }

    @Test fun askIsTheFirstChoiceInTheSettingsOrder() {
        assertEquals(TxtOpen.ASK, TxtOpen.entries.first())
    }
}
