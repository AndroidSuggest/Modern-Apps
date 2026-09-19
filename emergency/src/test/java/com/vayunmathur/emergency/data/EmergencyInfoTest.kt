package com.vayunmathur.emergency.data

import android.net.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trip and edge-case checks for the GrapheneOS-shaped contact storage:
 * `|`-separated phone URIs ([serializeContacts]/[parseContactUris]) and
 * [EmergencyInfo.hasAnythingSet] (drives the Settings suggestion alias).
 */
@RunWith(RobolectricTestRunner::class)
class EmergencyInfoTest {

    private fun uri(id: Int): Uri =
        Uri.parse("content://com.android.contacts/data/$id")

    @Test
    fun emptyListSerializesToEmptyString() {
        assertEquals("", serializeContacts(emptyList()))
    }

    @Test
    fun singleContactHasNoSeparator() {
        assertEquals(
            "content://com.android.contacts/data/7",
            serializeContacts(listOf(uri(7))),
        )
    }

    @Test
    fun multipleContactsJoinWithPipeAndRoundTrip() {
        val uris = listOf(uri(1), uri(2), uri(3))
        val serialized = serializeContacts(uris)
        assertEquals(
            "content://com.android.contacts/data/1" +
                "|content://com.android.contacts/data/2" +
                "|content://com.android.contacts/data/3",
            serialized,
        )
        assertEquals(uris, parseContactUris(serialized))
    }

    @Test
    fun parseDropsBlanks() {
        assertEquals(
            listOf(uri(1), uri(2)),
            parseContactUris("content://com.android.contacts/data/1||content://com.android.contacts/data/2|"),
        )
    }

    @Test
    fun parseEmptyStringGivesEmptyList() {
        assertEquals(emptyList(), parseContactUris(""))
    }

    @Test
    fun emptyInfoHasNothingSet() {
        assertFalse(EmergencyInfo().hasAnythingSet())
    }

    @Test
    fun anyFieldCountsAsSet() {
        assertTrue(EmergencyInfo(address = "1 Main St").hasAnythingSet())
        assertTrue(EmergencyInfo(bloodType = "O+").hasAnythingSet())
        assertTrue(EmergencyInfo(organDonor = "yes").hasAnythingSet())
        assertTrue(EmergencyInfo(name = "Sam").hasAnythingSet())
    }

    @Test
    fun normalizesBloodType() {
        assertEquals("O+", normalizeBloodType(" o+ "))
        assertEquals("AB-", normalizeBloodType("ab-"))
        assertEquals("", normalizeBloodType("unknown"))
        assertEquals("", normalizeBloodType("  "))
    }

    @Test
    fun normalizesOrganDonor() {
        assertEquals(ORGAN_DONOR_YES, normalizeOrganDonor("Yes"))
        assertEquals(ORGAN_DONOR_NO, normalizeOrganDonor(" NO "))
        assertEquals("", normalizeOrganDonor("unknown"))
        assertEquals("", normalizeOrganDonor(""))
    }

    @Test
    fun unknownMedicalFieldsDoNotCountAsSet() {
        assertFalse(EmergencyInfo(bloodType = "unknown").hasAnythingSet())
        assertFalse(EmergencyInfo(organDonor = "unknown").hasAnythingSet())
        assertFalse(EmergencyInfo(bloodType = "", organDonor = "").hasAnythingSet())
    }

    @Test
    fun blankStringsDoNotCountAsSet() {
        assertFalse(EmergencyInfo(address = "   ").hasAnythingSet())
    }

    @Test
    fun contactAloneCountsAsSet() {
        val contact = EmergencyContact(uri(1), "Alex Rivera", "(555) 010-2030", "Mobile")
        assertTrue(EmergencyInfo(contacts = listOf(contact)).hasAnythingSet())
    }
}
