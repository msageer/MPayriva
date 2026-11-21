package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class AccountType { INDIVIDUAL, BUSINESS }

enum class AccountStatus { ACTIVE, RESTRICTED, SUSPENDED }

enum class TransactionType(val label: String) {
    WALLET_FUNDING("Wallet Funding"),
    AIRTIME("Airtime"),
    DATA("Mobile Data"),
    ELECTRICITY("Electricity"),
    CABLE_TV("Cable TV"),
    RECHARGE_CARD("Recharge Card"),
    WAEC_PIN("WAEC PIN"),
    JAMB_PIN("JAMB PIN"),
    REFUND("Refund");

    companion object {
        fun safeValueOf(raw: String): TransactionType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AIRTIME
    }
}

enum class TransactionStatus(val label: String) {
    INITIATED("Initiated"),
    PROCESSING("Processing"),
    PENDING("Pending"),
    SUCCESSFUL("Successful"),
    FAILED("Failed"),
    REVERSED("Reversed");

    companion object {
        fun safeValueOf(raw: String): TransactionStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: PENDING
    }
}

enum class ServiceType(val label: String) {
    AIRTIME("Airtime"),
    DATA("Data Plans"),
    ELECTRICITY("Electricity"),
    CABLE_TV("Cable TV"),
    RECHARGE_CARD("Recharge Cards"),
    WAEC_PIN("WAEC PIN"),
    JAMB_PIN("JAMB PIN");

    companion object {
        fun safeValueOf(raw: String): ServiceType =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: AIRTIME
    }
}

enum class SupportCategory(val label: String) {
    FAILED_TRANSACTION("Failed Transaction"),
    WALLET_FUNDING("Wallet Funding"),
    UTILITY_TOKEN("Electricity / PIN Token"),
    ACCOUNT("Account & Security"),
    OTHER("Other Inquiry");

    companion object {
        fun safeValueOf(raw: String): SupportCategory =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OTHER
    }
}

enum class TicketStatus(val label: String) {
    OPEN("Open"),
    IN_PROGRESS("In Progress"),
    RESOLVED("Resolved"),
    CLOSED("Closed");

    companion object {
        fun safeValueOf(raw: String): TicketStatus =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: OPEN
    }
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val accountType: String = AccountType.INDIVIDUAL.name,
    val fullName: String = "",
    val email: String = "",
    val phoneNumber: String = "",
    val businessName: String? = null,
    val contactPerson: String? = null,
    val businessAddress: String? = null,
    val businessCategory: String? = null,
    val accountStatus: String = AccountStatus.ACTIVE.name,
    val restrictionReason: String? = null,
    val pinSet: Boolean = false,
    val biometricEnabled: Boolean = false,
    val kycVerified: Boolean = false,
    val walletBalanceKobo: Long = 0L,
    val virtualBankName: String? = null,
    val virtualAccountNumber: String? = null,
    val virtualAccountName: String? = null,
    val fcmToken: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun resolvedAccountType(): AccountType =
        AccountType.entries.firstOrNull { it.name == accountType } ?: AccountType.INDIVIDUAL

    fun resolvedAccountStatus(): AccountStatus =
        AccountStatus.entries.firstOrNull { it.name == accountStatus } ?: AccountStatus.ACTIVE

    fun displayTitle(): String =
        if (resolvedAccountType() == AccountType.BUSINESS && !businessName.isNullOrBlank()) {
            businessName
        } else {
            fullName.ifBlank { email.substringBefore("@") }
        }
}

@Keep
@IgnoreExtraProperties
data class SecurityPinRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val pinHash: String = "",
    val pinSalt: String = "",
    val failedAttempts: Long = 0L,
    val lockedUntilEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class TransactionRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val reference: String = "",
    val idempotencyKey: String = "",
    val type: String = TransactionType.AIRTIME.name,
    val status: String = TransactionStatus.PENDING.name,
    val provider: String = "CLUBKONNECT",
    val providerCode: String = "",
    val providerOrderId: String? = null,
    val recipient: String = "",
    val recipientName: String? = null,
    val productName: String = "",
    val productCode: String? = null,
    val providerCostKobo: Long = 0L,
    val sellingPriceKobo: Long = 0L,
    val feeKobo: Long = 0L,
    val grossMarginKobo: Long = 0L,
    val totalAmountKobo: Long = 0L,
    val tokenOrPin: String? = null,
    val serialNumber: String? = null,
    val failureReason: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun resolvedType(): TransactionType = TransactionType.safeValueOf(type)
    fun resolvedStatus(): TransactionStatus = TransactionStatus.safeValueOf(status)
    fun isCredit(): Boolean = resolvedType() == TransactionType.WALLET_FUNDING || resolvedType() == TransactionType.REFUND
}

@Keep
@IgnoreExtraProperties
data class LedgerEntry(
    @DocumentId val id: String = "",
    val userId: String = "",
    val transactionId: String = "",
    val reference: String = "",
    val entryType: String = "DEBIT",
    val accountCode: String = "CUSTOMER_WALLET",
    val amountKobo: Long = 0L,
    val balanceBeforeKobo: Long = 0L,
    val balanceAfterKobo: Long = 0L,
    val narration: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ServiceCatalogItem(
    @DocumentId val id: String = "",
    val serviceType: String = ServiceType.AIRTIME.name,
    val providerCode: String = "",
    val providerName: String = "",
    val productCode: String = "",
    val productName: String = "",
    val validity: String? = null,
    val dataVolume: String? = null,
    val providerCostKobo: Long = 0L,
    val sellingPriceKobo: Long = 0L,
    val feeKobo: Long = 0L,
    val discountPercent: Double = 0.0,
    val commissionPercent: Double = 0.0,
    val enabled: Boolean = true,
    val updatedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun resolvedServiceType(): ServiceType = ServiceType.safeValueOf(serviceType)
    fun grossMarginKobo(): Long = (sellingPriceKobo + feeKobo) - providerCostKobo
}

@Keep
@IgnoreExtraProperties
data class SupportTicket(
    @DocumentId val id: String = "",
    val userId: String = "",
    val userEmail: String = "",
    val userName: String = "",
    val category: String = SupportCategory.OTHER.name,
    val subject: String = "",
    val message: String = "",
    val transactionReference: String? = null,
    val status: String = TicketStatus.OPEN.name,
    val adminResponse: String? = null,
    val adminNotes: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun resolvedCategory(): SupportCategory = SupportCategory.safeValueOf(category)
    fun resolvedStatus(): TicketStatus = TicketStatus.safeValueOf(status)
}

@Keep
@IgnoreExtraProperties
data class CustomerNotification(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val body: String = "",
    val type: String = "ANNOUNCEMENT",
    val reference: String? = null,
    val read: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ProviderLog(
    @DocumentId val id: String = "",
    val provider: String = "CLUBKONNECT",
    val action: String = "",
    val reference: String = "",
    val statusCode: String = "",
    val outcome: String = "SUCCESS",
    val message: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PlatformConfig(
    @DocumentId val id: String = "",
    val brandName: String = "M-Sageer Pay",
    val companyLegalName: String = "M-SAGEER DIGITAL TECHNOLOGIES LTD",
    val supportEmail: String = "support@msageerdigital.com",
    val supportPhone: String = "+234 800 672 4337",
    val backendBaseUrl: String = "",
    val clubKonnectMode: String = "SANDBOX",
    val flutterwaveMode: String = "SANDBOX",
    val virtualAccountsEnabled: Boolean = true,
    val cardFundingEnabled: Boolean = true,
    val maintenanceMode: Boolean = false,
    val updatedBy: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
