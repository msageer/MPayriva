package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.firebase.extractFirestoreRootError
import com.example.data.model.AccountType
import com.example.data.model.CustomerNotification
import com.example.data.model.LedgerEntry
import com.example.data.model.PlatformConfig
import com.example.data.model.ServiceCatalogItem
import com.example.data.model.SupportCategory
import com.example.data.model.SupportTicket
import com.example.data.model.TransactionRecord
import com.example.data.model.UserProfile
import com.example.data.remote.CardFundingResponse
import com.example.data.remote.CustomerVerificationResponse
import com.example.data.remote.PurchaseRequest
import com.example.data.remote.PurchaseResponse
import com.example.data.remote.TrustedBackendClient
import com.example.data.remote.VirtualAccountResponse
import com.example.data.repository.FintechRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class ActionBanner(
    val message: String,
    val isError: Boolean = false
)

class FintechViewModel(
    private val repository: FintechRepository,
    private val backendClient: TrustedBackendClient,
    val currentUserId: String
) : ViewModel() {

    private val _isBackendConfigured = MutableStateFlow(backendClient.isConfigured)
    val isBackendConfigured: StateFlow<Boolean> = _isBackendConfigured.asStateFlow()

    private val _balanceVisible = MutableStateFlow(true)
    val balanceVisible: StateFlow<Boolean> = _balanceVisible.asStateFlow()

    private val initialBootstrappedAdmin = repository.isBootstrappedAdminSync()

    private val _adminResolutionState = MutableStateFlow<Boolean?>(if (initialBootstrappedAdmin) true else null)
    val adminResolutionState: StateFlow<Boolean?> = _adminResolutionState.asStateFlow()

    private val _isAdmin = MutableStateFlow(initialBootstrappedAdmin)
    val isAdmin: StateFlow<Boolean> = _isAdmin.asStateFlow()

    private val _actionBanner = MutableStateFlow<ActionBanner?>(null)
    val actionBanner: StateFlow<ActionBanner?> = _actionBanner.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _verifiedCustomer = MutableStateFlow<CustomerVerificationResponse?>(null)
    val verifiedCustomer: StateFlow<CustomerVerificationResponse?> = _verifiedCustomer.asStateFlow()

    private val _lastCardCheckout = MutableStateFlow<CardFundingResponse?>(null)
    val lastCardCheckout: StateFlow<CardFundingResponse?> = _lastCardCheckout.asStateFlow()

    val userProfileState: StateFlow<UiState<UserProfile?>> = repository.observeUserProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { error ->
            val root = extractFirestoreRootError(error)
            if (root == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing profile", root ?: error)
            emit(UiState.Error((root ?: error).message ?: "Unable to load profile"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val transactionsState: StateFlow<UiState<List<TransactionRecord>>> = repository.observeUserTransactions(currentUserId)
        .map<List<TransactionRecord>, UiState<List<TransactionRecord>>> { UiState.Success(it) }
        .catch { error ->
            val root = extractFirestoreRootError(error)
            if (root == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing transactions", root ?: error)
            emit(UiState.Error((root ?: error).message ?: "Unable to load transactions"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val ledgerState: StateFlow<UiState<List<LedgerEntry>>> = repository.observeUserLedger(currentUserId)
        .map<List<LedgerEntry>, UiState<List<LedgerEntry>>> { UiState.Success(it) }
        .catch { error ->
            val root = extractFirestoreRootError(error)
            if (root == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing ledger", root ?: error)
            emit(UiState.Error((root ?: error).message ?: "Unable to load wallet ledger"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val catalogState: StateFlow<UiState<List<ServiceCatalogItem>>> = repository.observeEnabledCatalog(currentUserId)
        .map<List<ServiceCatalogItem>, UiState<List<ServiceCatalogItem>>> { UiState.Success(it) }
        .catch { error ->
            val root = extractFirestoreRootError(error)
            if (root == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing catalog", root ?: error)
            emit(UiState.Error((root ?: error).message ?: "Unable to load services"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val supportTicketsState: StateFlow<UiState<List<SupportTicket>>> = repository.observeUserSupportTickets(currentUserId)
        .map<List<SupportTicket>, UiState<List<SupportTicket>>> { UiState.Success(it) }
        .catch { error ->
            val root = extractFirestoreRootError(error)
            if (root == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing support tickets", root ?: error)
            emit(UiState.Error((root ?: error).message ?: "Unable to load support tickets"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val notificationsState: StateFlow<UiState<List<CustomerNotification>>> = repository.observeUserNotifications(currentUserId)
        .map<List<CustomerNotification>, UiState<List<CustomerNotification>>> { UiState.Success(it) }
        .catch { error ->
            val root = extractFirestoreRootError(error)
            if (root == null && error is CancellationException) throw error
            Log.w(TAG, "Error observing notifications", root ?: error)
            emit(UiState.Error((root ?: error).message ?: "Unable to load notifications"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), UiState.Loading)

    val platformConfig: StateFlow<PlatformConfig> = repository.observeBrandingConfig(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlatformConfig())

    init {
        viewModelScope.launch {
            platformConfig.collect { cfg ->
                backendClient.updateBaseUrl(cfg.backendBaseUrl)
                _isBackendConfigured.value = backendClient.isConfigured
            }
        }
        if (!initialBootstrappedAdmin) {
            viewModelScope.launch {
                val resolved = repository.checkIsAdmin()
                _isAdmin.value = resolved
                _adminResolutionState.value = resolved
            }
        }
    }

    fun toggleBalanceVisibility() {
        _balanceVisible.value = !_balanceVisible.value
    }

    fun clearBanner() {
        _actionBanner.value = null
    }

    fun clearVerification() {
        _verifiedCustomer.value = null
    }

    fun clearCardCheckout() {
        _lastCardCheckout.value = null
    }

    fun completeRegistration(
        accountType: AccountType,
        fullName: String,
        phoneNumber: String,
        businessName: String?,
        contactPerson: String?,
        businessAddress: String?,
        businessCategory: String?,
        pin4Digits: String,
        onComplete: () -> Unit = {}
    ) {
        viewModelScope.launch {
            _isSubmitting.value = true
            val result = repository.createUserProfile(
                accountType = accountType,
                fullName = fullName,
                phoneNumber = phoneNumber,
                businessName = businessName,
                contactPerson = contactPerson,
                businessAddress = businessAddress,
                businessCategory = businessCategory,
                pin4Digits = pin4Digits
            )
            _isSubmitting.value = false
            result.onSuccess {
                _actionBanner.value = ActionBanner("Account created securely. Welcome to M-Sageer Pay!")
                onComplete()
            }.onFailure { err ->
                _actionBanner.value = ActionBanner(err.message ?: "Registration failed", isError = true)
            }
        }
    }

    fun updateProfile(
        fullName: String,
        phoneNumber: String,
        businessName: String?,
        contactPerson: String?,
        businessAddress: String?,
        businessCategory: String?,
        biometricEnabled: Boolean
    ) {
        viewModelScope.launch {
            _isSubmitting.value = true
            val result = repository.updateUserProfile(
                fullName = fullName,
                phoneNumber = phoneNumber,
                businessName = businessName,
                contactPerson = contactPerson,
                businessAddress = businessAddress,
                businessCategory = businessCategory,
                biometricEnabled = biometricEnabled
            )
            _isSubmitting.value = false
            result.onSuccess {
                _actionBanner.value = ActionBanner("Profile settings updated.")
            }.onFailure { err ->
                _actionBanner.value = ActionBanner(err.message ?: "Failed to update profile", isError = true)
            }
        }
    }

    fun changePin(currentPin: String, newPin: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isSubmitting.value = true
            val result = repository.changeTransactionPin(currentPin, newPin)
            _isSubmitting.value = false
            result.onSuccess {
                _actionBanner.value = ActionBanner("Transaction PIN updated securely.")
                onSuccess()
            }.onFailure { err ->
                _actionBanner.value = ActionBanner(err.message ?: "Could not update PIN", isError = true)
            }
        }
    }

    fun requestDedicatedVirtualAccount(bvn: String?) {
        viewModelScope.launch {
            _isSubmitting.value = true
            val result: Result<VirtualAccountResponse> = backendClient.requestVirtualAccount(bvn?.trim()?.takeIf { it.isNotEmpty() })
            _isSubmitting.value = false
            result.onSuccess { resp ->
                _actionBanner.value = ActionBanner(resp.message)
            }.onFailure { err ->
                _actionBanner.value = ActionBanner(err.message ?: "Unable to request virtual account", isError = true)
            }
        }
    }

    fun initiateCardWalletFunding(amountKobo: Long) {
        if (amountKobo < 10000L) {
            _actionBanner.value = ActionBanner("Minimum wallet funding amount is ₦100.00.", isError = true)
            return
        }
        viewModelScope.launch {
            _isSubmitting.value = true
            val idempotencyKey = "flw_${UUID.randomUUID()}"
            val result = backendClient.initiateCardFunding(amountKobo, idempotencyKey)
            _isSubmitting.value = false
            result.onSuccess { resp ->
                _lastCardCheckout.value = resp
                _actionBanner.value = ActionBanner(resp.message)
            }.onFailure { err ->
                _actionBanner.value = ActionBanner(err.message ?: "Unable to initialize card payment", isError = true)
            }
        }
    }

    fun verifyCardFunding(reference: String) {
        viewModelScope.launch {
            _isSubmitting.value = true
            val result = backendClient.verifyFunding(reference = reference)
            _isSubmitting.value = false
            result.onSuccess { resp ->
                if (resp.status == "SUCCESSFUL") {
                    _lastCardCheckout.value = null
                }
                _actionBanner.value = ActionBanner(resp.message)
            }.onFailure { err ->
                _actionBanner.value = ActionBanner(err.message ?: "Unable to verify payment status", isError = true)
            }
        }
    }

    fun verifyElectricityMeter(electricCompany: String, meterNo: String, meterType: String) {
        viewModelScope.launch {
            _isSubmitting.value = true
            val result = backendClient.verifyElectricityMeter(electricCompany, meterNo.trim(), meterType)
            _isSubmitting.value = false
            result.onSuccess { resp ->
                _verifiedCustomer.value = resp
                if (!resp.verified) {
                    _actionBanner.value = ActionBanner(resp.message ?: "Meter verification failed", isError = true)
                }
            }.onFailure { err ->
                _actionBanner.value = ActionBanner(err.message ?: "Meter verification unavailable", isError = true)
            }
        }
    }

    fun verifyCableSmartCard(cableTv: String, smartCardNo: String) {
        viewModelScope.launch {
            _isSubmitting.value = true
            val result = backendClient.verifyCableSmartCard(cableTv, smartCardNo.trim())
            _isSubmitting.value = false
            result.onSuccess { resp ->
                _verifiedCustomer.value = resp
                if (!resp.verified) {
                    _actionBanner.value = ActionBanner(resp.message ?: "Smartcard verification failed", isError = true)
                }
            }.onFailure { err ->
                _actionBanner.value = ActionBanner(err.message ?: "Smartcard verification unavailable", isError = true)
            }
        }
    }

    /**
     * Executes the 13-step purchase pipeline:
     * 1. Verifies Transaction PIN against salted hash (with lockout protection).
     * 2. Validates wallet balance client-side as UX guard (backend remains financial authority).
     * 3. Submits request with unique idempotencyKey to trusted backend.
     * NEVER modifies wallet balances or fakes successful transactions on the Android client.
     */
    fun authorizeAndSubmitPurchase(
        serviceType: String,
        providerCode: String,
        productCode: String,
        recipient: String,
        amountKobo: Long,
        meterType: String? = null,
        quantity: Int = 1,
        enteredPin: String,
        idempotencyKey: String,
        onFinished: (Result<PurchaseResponse>) -> Unit
    ) {
        viewModelScope.launch {
            _isSubmitting.value = true
            val pinCheck = repository.verifyTransactionPinLocally(enteredPin)
            val pinValid = pinCheck.getOrElse { err ->
                _isSubmitting.value = false
                _actionBanner.value = ActionBanner(err.message ?: "PIN check failed", isError = true)
                onFinished(Result.failure(err))
                return@launch
            }
            if (!pinValid) {
                _isSubmitting.value = false
                val err = IllegalArgumentException("Incorrect Transaction PIN. Please try again.")
                _actionBanner.value = ActionBanner(err.message!!, isError = true)
                onFinished(Result.failure(err))
                return@launch
            }

            val profile = (userProfileState.value as? UiState.Success)?.data
            if (profile != null && profile.walletBalanceKobo < amountKobo) {
                _isSubmitting.value = false
                val err = IllegalStateException("Insufficient wallet balance. Please fund your wallet first.")
                _actionBanner.value = ActionBanner(err.message!!, isError = true)
                onFinished(Result.failure(err))
                return@launch
            }

            val responseResult = backendClient.submitPurchase(
                PurchaseRequest(
                    idempotencyKey = idempotencyKey,
                    serviceType = serviceType,
                    providerCode = providerCode,
                    productCode = productCode,
                    recipient = recipient.trim(),
                    amountKobo = amountKobo,
                    meterType = meterType,
                    quantity = quantity,
                    transactionPin = enteredPin
                )
            )
            _isSubmitting.value = false
            responseResult.onSuccess { resp ->
                _actionBanner.value = ActionBanner(resp.message)
            }.onFailure { err ->
                _actionBanner.value = ActionBanner(err.message ?: "Transaction request failed", isError = true)
            }
            onFinished(responseResult)
        }
    }

    fun requeryTransaction(reference: String) {
        viewModelScope.launch {
            _isSubmitting.value = true
            val result = backendClient.requeryTransaction(reference)
            _isSubmitting.value = false
            result.onSuccess { resp ->
                _actionBanner.value = ActionBanner("Status checked: ${resp.status} — ${resp.message}")
            }.onFailure { err ->
                _actionBanner.value = ActionBanner(err.message ?: "Unable to requery transaction", isError = true)
            }
        }
    }

    fun submitSupportTicket(
        category: SupportCategory,
        subject: String,
        message: String,
        transactionReference: String?,
        onSuccess: () -> Unit
    ) {
        viewModelScope.launch {
            _isSubmitting.value = true
            val profile = (userProfileState.value as? UiState.Success)?.data
            val result = repository.createSupportTicket(
                userName = profile?.displayTitle() ?: "Customer",
                category = category,
                subject = subject,
                message = message,
                transactionReference = transactionReference
            )
            _isSubmitting.value = false
            result.onSuccess {
                _actionBanner.value = ActionBanner("Support ticket submitted. Our team will respond shortly.")
                onSuccess()
            }.onFailure { err ->
                _actionBanner.value = ActionBanner(err.message ?: "Unable to submit ticket", isError = true)
            }
        }
    }

    fun markNotificationRead(notificationId: String) {
        viewModelScope.launch {
            repository.markNotificationRead(notificationId)
        }
    }

    private companion object {
        const val TAG = "FintechVM"
        const val STOP_TIMEOUT_MS = 5000L
    }
}
