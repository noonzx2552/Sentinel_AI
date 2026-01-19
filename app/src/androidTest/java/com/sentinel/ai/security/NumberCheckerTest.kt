package com.sentinel.ai.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class NumberCheckerTest {

    @Test
    fun thaiTenDigitNumberParsesWithFallbackRegion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val checker = NumberChecker(context, enableExternalLookups = false)
            val result = checker.check("0936130173")
            assertTrue(result.formattedE164.startsWith("+66"))
            assertTrue(result.displayNumber.isNotBlank())
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
