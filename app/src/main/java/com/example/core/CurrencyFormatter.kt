package com.example.core

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Precision integer-kobo formatter for Nigerian Naira (₦).
 * All wallet balances and prices are stored in Long kobo (100 kobo = ₦1.00)
 * to eliminate floating-point rounding drift.
 */
object CurrencyFormatter {
    private val ngLocale = Locale("en", "NG")

    fun formatKobo(kobo: Long, showSymbol: Boolean = true): String {
        val naira = kobo / 100.0
        val formatter = NumberFormat.getNumberInstance(ngLocale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
        }
        val formatted = formatter.format(naira)
        return if (showSymbol) "₦$formatted" else formatted
    }

    fun nairaToKobo(nairaText: String): Long? {
        val cleaned = nairaText.replace(",", "").replace("₦", "").trim()
        if (cleaned.isEmpty()) return null
        val value = cleaned.toDoubleOrNull() ?: return null
        if (value < 0.0) return null
        return Math.round(value * 100.0)
    }

    fun formatDateTime(date: Date?): String {
        if (date == null) return "Just now"
        val sdf = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        return sdf.format(date)
    }

    fun formatShortDate(date: Date?): String {
        if (date == null) return "Just now"
        val sdf = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault())
        return sdf.format(date)
    }

    fun isValidNigerianPhone(phone: String): Boolean {
        val digits = phone.replace(" ", "").replace("-", "")
        return Regex("^(\\+234|234|0)[789][01]\\d{8}$").matches(digits)
    }

    fun normalizeNigerianPhone(phone: String): String {
        val digits = phone.replace(" ", "").replace("-", "")
        return when {
            digits.startsWith("+234") -> digits
            digits.startsWith("234") -> "+$digits"
            digits.startsWith("0") && digits.length == 11 -> "+234${digits.drop(1)}"
            else -> digits
        }
    }

    fun toLocalElevenDigitPhone(phone: String): String {
        val normalized = normalizeNigerianPhone(phone)
        return if (normalized.startsWith("+234") && normalized.length == 14) {
            "0${normalized.removePrefix("+234")}"
        } else {
            phone.trim()
        }
    }
}
