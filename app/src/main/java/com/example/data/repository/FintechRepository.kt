package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.core.BrandingConfig
import com.example.core.CurrencyFormatter
import com.example.core.PinHasher
import com.example.data.firebase.OperationType
import com.example.data.firebase.extractFirestoreRootError
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.AccountStatus
import com.example.data.model.AccountType
import com.example.data.model.CustomerNotification
import com.example.data.model.LedgerEntry
import com.example.data.model.NigerianProviders
import com.example.data.model.PlatformConfig
import com.example.data.model.ProviderLog
import com.example.data.model.SecurityPinRecord
import com.example.data.model.ServiceCatalogItem
import com.example.data.model.SupportCategory
import com.example.data.model.SupportTicket
import com.example.data.model.TicketStatus
import com.example.data.model.TransactionRecord
import com.example.data.model.UserProfile
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Central Firestore repository for M-Sageer Pay.
 * Always connects to the provisioned enterprise database via R.string.firestore_database_id.
 * Wallet balances, transactions, and ledger entries are read-only from the Android client.
 */
class FintechRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        val uid = auth.currentUser?.uid
        check(!uid.isNullOrBlank()) { "User must be signed in with Google before accessing Firestore." }
        return uid
    }

    // --- ROLE CHECK ---
    fun isBootstrappedAdminSync(): Boolean {
        val user = auth.currentUser ?: return false
        return user.email.equals(BrandingConfig.Default.bootstrappedAdminEmail, ignoreCase = true) && user.isEmailVerified
    }

    suspend fun checkIsAdmin(): Boolean {
        val user = auth.currentUser ?: return false
        if (isBootstrappedAdminSync()) {
            return true
        }
        val path = "admins/${user.uid}"
        return try {
            db.collection("admins").document(user.uid).get().await().exists()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            false
        }
    }

    // --- USER PROFILE ---
    fun observeUserProfile(userId: String): Flow<UserProfile?> = flow {
        require(userId.isNotBlank()) { "userId must not be blank" }
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { snap ->
                    if (snap.exists()) {
                        snap.toObject(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    } else null
                }
                .catch { error ->
                    val root = extractFirestoreRootError(error) ?: throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(root, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createUserProfile(
        accountType: AccountType,
        fullName: String,
        phoneNumber: String,
        businessName: String? = null,
        contactPerson: String? = null,
        businessAddress: String? = null,
        businessCategory: String? = null,
        pin4Digits: String
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val email = auth.currentUser?.email ?: return Result.failure(IllegalStateException("Missing account email"))
        val userPath = "users/$uid"
        val pinPath = "users/$uid/security/pin"
        return try {
            val trimmedName = fullName.trim()
            val normalizedPhone = CurrencyFormatter.normalizeNigerianPhone(phoneNumber.trim())
            require(trimmedName.length in 2..100) { "Full name must be between 2 and 100 characters." }
            require(CurrencyFormatter.isValidNigerianPhone(normalizedPhone)) { "Enter a valid Nigerian phone number (e.g. 08012345678)." }
            require(pin4Digits.length == 4 && pin4Digits.all { it.isDigit() }) { "Transaction PIN must be exactly 4 digits." }
            if (accountType == AccountType.BUSINESS) {
                require(!businessName.isNullOrBlank() && businessName.trim().length in 2..120) {
                    "Business name is required for Business accounts."
                }
                require(!businessAddress.isNullOrBlank() && businessAddress.trim().length in 5..250) {
                    "Business address is required for Business accounts."
                }
            }

            val salt = PinHasher.generateSaltHex()
            val pinHash = PinHasher.hashPin(pin4Digits, salt)

            val userPayload = mapOf(
                "userId" to uid,
                "accountType" to accountType.name,
                "fullName" to trimmedName,
                "email" to email,
                "phoneNumber" to normalizedPhone,
                "businessName" to businessName?.trim()?.takeIf { it.isNotEmpty() },
                "contactPerson" to contactPerson?.trim()?.takeIf { it.isNotEmpty() },
                "businessAddress" to businessAddress?.trim()?.takeIf { it.isNotEmpty() },
                "businessCategory" to businessCategory?.trim()?.takeIf { it.isNotEmpty() },
                "accountStatus" to AccountStatus.ACTIVE.name,
                "pinSet" to true,
                "biometricEnabled" to false,
                "kycVerified" to false,
                "walletBalanceKobo" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            val pinPayload = mapOf(
                "userId" to uid,
                "pinHash" to pinHash,
                "pinSalt" to salt,
                "failedAttempts" to 0L,
                "lockedUntilEpochMs" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            val batch = db.batch()
            batch.set(db.collection("users").document(uid), userPayload)
            batch.set(db.collection("users").document(uid).collection("security").document("pin"), pinPayload)
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, "$userPath | $pinPath")
            Result.failure(e)
        }
    }

    suspend fun updateUserProfile(
        fullName: String,
        phoneNumber: String,
        businessName: String? = null,
        contactPerson: String? = null,
        businessAddress: String? = null,
        businessCategory: String? = null,
        biometricEnabled: Boolean
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid"
        return try {
            val trimmedName = fullName.trim()
            val normalizedPhone = CurrencyFormatter.normalizeNigerianPhone(phoneNumber.trim())
            require(trimmedName.length in 2..100) { "Full name must be between 2 and 100 characters." }
            require(CurrencyFormatter.isValidNigerianPhone(normalizedPhone)) { "Enter a valid Nigerian phone number." }

            val updates = mutableMapOf<String, Any>(
                "fullName" to trimmedName,
                "phoneNumber" to normalizedPhone,
                "biometricEnabled" to biometricEnabled,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            businessName?.trim()?.takeIf { it.isNotEmpty() }?.let { updates["businessName"] = it }
            contactPerson?.trim()?.takeIf { it.isNotEmpty() }?.let { updates["contactPerson"] = it }
            businessAddress?.trim()?.takeIf { it.isNotEmpty() }?.let { updates["businessAddress"] = it }
            businessCategory?.trim()?.takeIf { it.isNotEmpty() }?.let { updates["businessCategory"] = it }

            db.collection("users").document(uid).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun saveFcmToken(token: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.success(Unit)
        val cleanToken = token.trim()
        if (cleanToken.isEmpty() || cleanToken.length > 300) return Result.success(Unit)
        val docRef = db.collection("users").document(uid)
        return try {
            val snap = docRef.get().await()
            if (!snap.exists()) return Result.success(Unit)
            if (snap.getString("fcmToken") == cleanToken) return Result.success(Unit)
            docRef.update(
                mapOf(
                    "fcmToken" to cleanToken,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun verifyTransactionPinLocally(enteredPin: String): Result<Boolean> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid/security/pin"
        return try {
            val snap = db.collection("users").document(uid).collection("security").document("pin").get().await()
            val record = snap.toObject(SecurityPinRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?: return Result.failure(IllegalStateException("Transaction PIN is not configured yet."))

            val nowMs = System.currentTimeMillis()
            if (record.lockedUntilEpochMs > nowMs) {
                val minsLeft = ((record.lockedUntilEpochMs - nowMs) / 60000L) + 1
                return Result.failure(IllegalStateException("PIN locked due to too many attempts. Try again in $minsLeft min."))
            }

            val valid = PinHasher.verifyPin(enteredPin, record.pinSalt, record.pinHash)
            if (valid) {
                if (record.failedAttempts > 0L) {
                    db.collection("users").document(uid).collection("security").document("pin")
                        .update(mapOf("failedAttempts" to 0L, "lockedUntilEpochMs" to 0L, "updatedAt" to FieldValue.serverTimestamp()))
                        .await()
                }
                Result.success(true)
            } else {
                val nextAttempts = (record.failedAttempts + 1L).coerceAtMost(5L)
                val lockUntil = if (nextAttempts >= 5L) nowMs + 15 * 60 * 1000L else 0L
                db.collection("users").document(uid).collection("security").document("pin")
                    .update(mapOf("failedAttempts" to nextAttempts, "lockedUntilEpochMs" to lockUntil, "updatedAt" to FieldValue.serverTimestamp()))
                    .await()
                Result.success(false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.GET, path)
            Result.failure(e)
        }
    }

    suspend fun changeTransactionPin(currentPin: String, newPin: String): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val path = "users/$uid/security/pin"
        return try {
            require(newPin.length == 4 && newPin.all { it.isDigit() }) { "New PIN must be 4 digits." }
            val verified = verifyTransactionPinLocally(currentPin).getOrThrow()
            if (!verified) return Result.failure(IllegalArgumentException("Current PIN is incorrect."))

            val salt = PinHasher.generateSaltHex()
            val hash = PinHasher.hashPin(newPin, salt)
            db.collection("users").document(uid).collection("security").document("pin")
                .update(
                    mapOf(
                        "pinHash" to hash,
                        "pinSalt" to salt,
                        "failedAttempts" to 0L,
                        "lockedUntilEpochMs" to 0L,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- TRANSACTIONS & DOUBLE-ENTRY LEDGER (READ-ONLY FOR CLIENT) ---
    fun observeUserTransactions(userId: String): Flow<List<TransactionRecord>> = flow {
        require(userId.isNotBlank())
        val path = "transactions"
        emitAll(
            db.collection(path)
                .whereEqualTo("userId", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(TransactionRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val root = extractFirestoreRootError(error) ?: throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(root, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeUserLedger(userId: String): Flow<List<LedgerEntry>> = flow {
        require(userId.isNotBlank())
        val path = "ledgerEntries"
        emitAll(
            db.collection(path)
                .whereEqualTo("userId", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(LedgerEntry::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val root = extractFirestoreRootError(error) ?: throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(root, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    // --- SERVICE CATALOG (ENABLED PRODUCTS FOR CUSTOMERS) ---
    fun observeEnabledCatalog(userId: String): Flow<List<ServiceCatalogItem>> = flow {
        require(userId.isNotBlank())
        val path = "serviceCatalog"
        emitAll(
            db.collection(path)
                .whereEqualTo("enabled", true)
                .snapshots()
                .map { snap ->
                    snap.toObjects(ServiceCatalogItem::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedWith(compareBy({ it.serviceType }, { it.providerName }, { it.sellingPriceKobo }))
                }
                .catch { error ->
                    val root = extractFirestoreRootError(error) ?: throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(root, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    // --- CUSTOMER SUPPORT TICKETS ---
    fun observeUserSupportTickets(userId: String): Flow<List<SupportTicket>> = flow {
        require(userId.isNotBlank())
        val path = "supportTickets"
        emitAll(
            db.collection(path)
                .whereEqualTo("userId", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(SupportTicket::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val root = extractFirestoreRootError(error) ?: throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(root, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createSupportTicket(
        userName: String,
        category: SupportCategory,
        subject: String,
        message: String,
        transactionReference: String?
    ): Result<Unit> {
        val uid = auth.currentUser?.uid ?: return Result.failure(IllegalStateException("Not signed in"))
        val email = auth.currentUser?.email ?: "customer@msageerdigital.com"
        val docRef = db.collection("supportTickets").document()
        return try {
            val cleanSubject = subject.trim()
            val cleanMessage = message.trim()
            require(cleanSubject.length in 3..120) { "Subject must be between 3 and 120 characters." }
            require(cleanMessage.length in 10..1500) { "Please describe your issue (at least 10 characters)." }

            val payload = mapOf(
                "userId" to uid,
                "userEmail" to email,
                "userName" to userName.ifBlank { email.substringBefore("@") }.take(100),
                "category" to category.name,
                "subject" to cleanSubject,
                "message" to cleanMessage,
                "transactionReference" to transactionReference?.trim()?.takeIf { it.isNotEmpty() },
                "status" to TicketStatus.OPEN.name,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ).filterValues { it != null }

            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    // --- NOTIFICATIONS ---
    fun observeUserNotifications(userId: String): Flow<List<CustomerNotification>> = flow {
        require(userId.isNotBlank())
        val path = "notifications"
        emitAll(
            db.collection(path)
                .whereEqualTo("userId", userId)
                .snapshots()
                .map { snap ->
                    snap.toObjects(CustomerNotification::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val root = extractFirestoreRootError(error) ?: throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(root, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun markNotificationRead(notificationId: String): Result<Unit> {
        val uid = requireUserId()
        val path = "notifications/$notificationId"
        return try {
            require(notificationId.isNotBlank())
            db.collection("notifications").document(notificationId)
                .update(mapOf("read" to true, "updatedAt" to FieldValue.serverTimestamp()))
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (auth.currentUser?.uid == uid) handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    // --- PLATFORM BRANDING CONFIG ---
    fun observeBrandingConfig(userId: String): Flow<PlatformConfig> = flow {
        require(userId.isNotBlank())
        val path = "platformConfig/branding"
        emitAll(
            db.collection("platformConfig").document("branding")
                .snapshots()
                .map { snap ->
                    snap.toObject(PlatformConfig::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        ?: PlatformConfig()
                }
                .catch { error ->
                    val root = extractFirestoreRootError(error) ?: throw error
                    if (auth.currentUser?.uid == userId) {
                        handleFirestoreError(root, OperationType.GET, path)
                    }
                    emit(PlatformConfig())
                }
        )
    }

    // =========================================================================
    // ADMIN-ONLY OPERATIONS (Strictly enforced by Firestore isAdmin() rules)
    // =========================================================================

    fun observeAllUsersAdmin(): Flow<List<UserProfile>> =
        db.collection("users")
            .snapshots()
            .map { snap ->
                snap.toObjects(UserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }

    fun observeAllTransactionsAdmin(): Flow<List<TransactionRecord>> =
        db.collection("transactions")
            .snapshots()
            .map { snap ->
                snap.toObjects(TransactionRecord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }

    fun observeAllCatalogAdmin(): Flow<List<ServiceCatalogItem>> =
        db.collection("serviceCatalog")
            .snapshots()
            .map { snap ->
                snap.toObjects(ServiceCatalogItem::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedWith(compareBy({ it.serviceType }, { it.providerName }, { it.sellingPriceKobo }))
            }

    fun observeAllSupportTicketsAdmin(): Flow<List<SupportTicket>> =
        db.collection("supportTickets")
            .snapshots()
            .map { snap ->
                snap.toObjects(SupportTicket::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }

    fun observeProviderLogsAdmin(): Flow<List<ProviderLog>> =
        db.collection("providerLogs")
            .snapshots()
            .map { snap ->
                snap.toObjects(ProviderLog::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                    .sortedByDescending { it.createdAt?.seconds ?: 0L }
            }

    suspend fun adminSeedDefaultCatalog(): Result<Int> {
        val adminUid = requireUserId()
        return try {
            val existingIds = db.collection("serviceCatalog").get().await().documents.map { it.id }.toSet()
            val items = NigerianProviders.defaultAdminSeedCatalog(adminUid)
                .filterNot { (docId, _) -> docId in existingIds }
            if (items.isEmpty()) return Result.success(0)
            val batch = db.batch()
            items.forEach { (docId, fields) ->
                val fullMap = fields.toMutableMap().apply {
                    put("createdAt", FieldValue.serverTimestamp())
                    put("updatedAt", FieldValue.serverTimestamp())
                }
                batch.set(db.collection("serviceCatalog").document(docId), fullMap)
            }
            batch.commit().await()
            Result.success(items.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "serviceCatalog")
            Result.failure(e)
        }
    }

    suspend fun adminUpdateCatalogPricing(
        serviceId: String,
        providerCostKobo: Long,
        sellingPriceKobo: Long,
        feeKobo: Long,
        discountPercent: Double,
        commissionPercent: Double,
        enabled: Boolean
    ): Result<Unit> {
        val adminUid = requireUserId()
        val path = "serviceCatalog/$serviceId"
        return try {
            require(providerCostKobo >= 0 && sellingPriceKobo >= 0 && feeKobo >= 0) { "Amounts must be non-negative." }
            db.collection("serviceCatalog").document(serviceId).update(
                mapOf(
                    "providerCostKobo" to providerCostKobo,
                    "sellingPriceKobo" to sellingPriceKobo,
                    "feeKobo" to feeKobo,
                    "discountPercent" to discountPercent.coerceIn(0.0, 50.0),
                    "commissionPercent" to commissionPercent.coerceIn(0.0, 50.0),
                    "enabled" to enabled,
                    "updatedBy" to adminUid,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun adminUpdateCustomerStatus(
        targetUserId: String,
        status: AccountStatus,
        restrictionReason: String?
    ): Result<Unit> {
        val path = "users/$targetUserId"
        return try {
            requireUserId()
            val payload = mapOf(
                "accountStatus" to status.name,
                "restrictionReason" to (restrictionReason?.trim()?.takeIf { it.isNotEmpty() } ?: ""),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection("users").document(targetUserId).update(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun adminRespondToTicket(
        ticketId: String,
        newStatus: TicketStatus,
        response: String,
        internalNotes: String?
    ): Result<Unit> {
        val path = "supportTickets/$ticketId"
        return try {
            requireUserId()
            val updates = mutableMapOf<String, Any>(
                "status" to newStatus.name,
                "adminResponse" to response.trim().take(1500),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (!internalNotes.isNullOrBlank()) {
                updates["adminNotes"] = internalNotes.trim().take(1500)
            }
            db.collection("supportTickets").document(ticketId).update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun adminSendNotification(
        targetUserId: String,
        title: String,
        body: String
    ): Result<Unit> {
        val docRef = db.collection("notifications").document()
        return try {
            requireUserId()
            require(title.trim().length in 2..120) { "Title must be 2–120 characters." }
            require(body.trim().length in 2..600) { "Message must be 2–600 characters." }
            docRef.set(
                mapOf(
                    "userId" to targetUserId,
                    "title" to title.trim(),
                    "body" to body.trim(),
                    "type" to "ANNOUNCEMENT",
                    "read" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun adminSavePlatformConfig(
        brandName: String,
        companyLegalName: String,
        supportEmail: String,
        supportPhone: String,
        backendBaseUrl: String,
        clubKonnectMode: String,
        flutterwaveMode: String,
        virtualAccountsEnabled: Boolean,
        cardFundingEnabled: Boolean,
        maintenanceMode: Boolean,
        enabledServices: Map<String, Boolean>? = null
    ): Result<Unit> {
        val adminUid = requireUserId()
        val docRef = db.collection("platformConfig").document("branding")
        return try {
            val cleanUrl = backendBaseUrl.trim().trimEnd('/')
            if (cleanUrl.isNotEmpty()) {
                require(
                    cleanUrl.startsWith("https://") || cleanUrl.startsWith("http://10.0.2.2")
                ) {
                    "Backend URL must start with https:// (or http://10.0.2.2 for local emulator testing)."
                }
            }
            val existingSnap = docRef.get().await()
            val commonFields = mutableMapOf<String, Any>(
                "brandName" to brandName.trim().ifBlank { BrandingConfig.Default.appDisplayName }.take(60),
                "companyLegalName" to companyLegalName.trim().ifBlank { BrandingConfig.Default.companyLegalName }.take(120),
                "supportEmail" to supportEmail.trim().ifBlank { BrandingConfig.Default.supportEmail }.take(120),
                "supportPhone" to supportPhone.trim().ifBlank { BrandingConfig.Default.supportPhone }.take(30),
                "backendBaseUrl" to cleanUrl.take(240),
                "clubKonnectMode" to if (clubKonnectMode == "LIVE") "LIVE" else "SANDBOX",
                "flutterwaveMode" to if (flutterwaveMode == "LIVE") "LIVE" else "SANDBOX",
                "virtualAccountsEnabled" to virtualAccountsEnabled,
                "cardFundingEnabled" to cardFundingEnabled,
                "maintenanceMode" to maintenanceMode,
                "updatedBy" to adminUid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (enabledServices != null) {
                commonFields["enabledServices"] = enabledServices
            }
            if (existingSnap.exists()) {
                docRef.update(commonFields).await()
            } else {
                commonFields["createdAt"] = FieldValue.serverTimestamp()
                docRef.set(commonFields).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docRef.path)
            Result.failure(e)
        }
    }

    suspend fun adminSaveServiceToggles(
        currentConfig: PlatformConfig,
        enabledServices: Map<String, Boolean>
    ): Result<Unit> = adminSavePlatformConfig(
        brandName = currentConfig.brandName,
        companyLegalName = currentConfig.companyLegalName,
        supportEmail = currentConfig.supportEmail,
        supportPhone = currentConfig.supportPhone,
        backendBaseUrl = currentConfig.backendBaseUrl,
        clubKonnectMode = currentConfig.clubKonnectMode,
        flutterwaveMode = currentConfig.flutterwaveMode,
        virtualAccountsEnabled = currentConfig.virtualAccountsEnabled,
        cardFundingEnabled = currentConfig.cardFundingEnabled,
        maintenanceMode = currentConfig.maintenanceMode,
        enabledServices = enabledServices
    )
}
