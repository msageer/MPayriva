package com.example.core

/**
 * Configurable branding & legal identity for the application.
 * Brand name can be overridden dynamically from Firestore /platformConfig/branding.
 */
data class BrandIdentity(
    val appDisplayName: String = "M-Sageer Pay",
    val companyLegalName: String = "M-SAGEER DIGITAL TECHNOLOGIES LTD",
    val tagline: String = "Instant Naira Wallet & Digital Utility Infrastructure",
    val currencyCode: String = "NGN",
    val currencySymbol: String = "₦",
    val supportEmail: String = "support@msageerdigital.com",
    val supportPhone: String = "+234 800 672 4337",
    val countryCode: String = "+234",
    val bootstrappedAdminEmail: String = "msagirgroup@gmail.com"
)

object BrandingConfig {
    val Default = BrandIdentity()
}
