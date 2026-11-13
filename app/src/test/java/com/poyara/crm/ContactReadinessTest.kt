package com.poyara.crm

import org.junit.Assert.*
import org.junit.Test

class ContactReadinessTest {
    @Test fun incompleteSpeechNeverBecomesCustomer() {
        assertFalse(ContactReadiness.isComplete("", ""))
        assertFalse(ContactReadiness.isComplete("صدای ضبط‌شده آماده پردازش است", ""))
        assertFalse(ContactReadiness.isComplete("آقای احمدی", ""))
        assertFalse(ContactReadiness.isComplete("", "09158151204"))
        assertFalse(ContactReadiness.isComplete("آقای احمدی", "123"))
    }

    @Test fun acceptsPersianMobileAndFixedLineWithName() {
        assertTrue(ContactReadiness.isComplete("آقای احمدی", "۰۹۱۵۸۱۵۱۲۰۴"))
        assertTrue(ContactReadiness.isComplete("مهندس حسینی", "۰۵۱۳۴۲۲۵۰۵۳"))
        assertTrue(ContactReadiness.isComplete("احمدی", "+989158151204"))
    }
}
