package com.urlxl.mail.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingValidatorTest {

    @Test
    fun validate_requiredFields() {
        assertFalse(PairingValidator.validate("").isValid)
        assertTrue(PairingValidator.validate("sub").isValid)
    }

    @Test
    fun validate_missingSub_returnsSpecificMessage() {
        val result = PairingValidator.validate("")

        assertFalse(result.isValid)
        assertEquals("Missing sub parameter", result.message)
    }
}
