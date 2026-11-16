package com.poyara.crm

import org.junit.Assert.*
import org.junit.Test

class PhoneContactImporterTest {
    private fun entry(id: Long, phone: String, extra: List<String> = emptyList()) =
        ContactEntry(id=id,name="مشتری قدیمی",phone=phone,note="",company="",
            tags=emptySet(),updatedAt=0L,extraPhones=extra)

    @Test fun supportsSelectedMultiplePeopleAndAllTheirNumbers() {
        val contacts = listOf(
            PhoneContactCandidate(10L, "احمدی", listOf("09150000001","05132222222")),
            PhoneContactCandidate(11L, "حسینی", listOf("09150000002"))
        )
        val selected = PhoneContactImporter.selectNew(contacts, setOf(10L,11L), emptyList())
        assertEquals(2,selected.size)
        assertEquals(2,selected.first().phones.size)
    }

    @Test fun skipsAlreadyImportedSystemContactPhone() {
        val contacts = listOf(
            PhoneContactCandidate(10L,"احمدی",listOf("+989150000001","05132222222")),
            PhoneContactCandidate(11L,"حسینی",listOf("09150000002"))
        )
        val result = PhoneContactImporter.selectNew(contacts,setOf(10L),listOf(
            entry(1L,"09150000001",listOf("05132222222"))
        ))
        assertTrue(result.isEmpty())
    }

    @Test fun skipOnlyExactDuplicatePhoneNotUnrelatedContact() {
        val candidates=listOf(
            PhoneContactCandidate(1L,"آقای الف",listOf("09150000001")),
            PhoneContactCandidate(2L,"آقای ب",listOf("09150000002"))
        )
        val result=PhoneContactImporter.selectNew(candidates,setOf(1L,2L),
            listOf(entry(5L,"09150000001")))
        assertEquals(listOf("آقای ب"),result.map { it.displayName })
    }
}
