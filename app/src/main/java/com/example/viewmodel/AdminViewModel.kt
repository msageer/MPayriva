package com.example.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.firebase.extractFirestoreRootError
import com.example.data.model.AccountStatus
import com.example.data.model.PlatformConfig
import com.example.data.model.ProviderLog
import com.example.data.model.ServiceCatalogItem
import com.example.data.model.SupportTicket
import com.example.data.model.TicketStatus
import com.example.data.model.TransactionRecord
import com.example.data.model.UserProfile
import com.example.data.remote.ProviderHealthResponse
import com.example.data.remote.TrustedBackendClient
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

class AdminViewModel(
    private val repository: FintechRepository,
    private val backendClient: TrustedBackendClient,
    adminUserId: String = "admin"
) : ViewModel() {

    private val _statusBanner = MutableStateFlow<ActionBanner?>(null)
    val statusBanner: StateFlow<ActionBanner?> = _statusBanner.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _providerHealth = MutableStateFlow<ProviderHealthResponse?>(null)
    val providerHealth: StateFlow<ProviderHealthResponse?> = _providerHealth.asStateFlow()

    private val _activeBackendUrl = MutableStateFlow(backendClient.activeBaseUrl)
    val activeBackendUrl: StateFlow<String> = _activeBackendUrl.asStateFlow()

    val platformConfig: StateFlow<PlatformConfig> = repository.observeBrandingConfig(adminUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), PlatformConfig())

    val allUsersState: StateFlow<UiState<List<UserProfile>>> = repository.observeAllUsersAdmin()
        .map<List<UserProfile>, UiState<List<UserProfile>>> { UiState.Success(it) }
        .catch { error ->
            val root = extractFirestoreRootError(error)
            if (root == null && error is CancellationException) throw error
            Log.w(TAG, "Admin users observer failed", root ?: error)
            emit(UiState.Error((root ?: error).message ?: "Admin access denied"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val allTransactionsState: StateFlow<UiState<List<TransactionRecord>>> = repository.observeAllTransactionsAdmin()
        .map<List<TransactionRecord>, UiState<List<TransactionRecord>>> { UiState.Success(it) }
        .catch { error ->
            val root = extractFirestoreRootError(error)
            if (root == null && error is CancellationException) throw error
            emit(UiState.Error((root ?: error).message ?: "Unable to load transactions"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val allCatalogState: StateFlow<UiState<List<ServiceCatalogItem>>> = repository.observeAllCatalogAdmin()
        .map<List<ServiceCatalogItem>, UiState<List<ServiceCatalogItem>>> { UiState.Success(it) }
        .catch { error ->
            val root = extractFirestoreRootError(error)
            if (root == null && error is CancellationException) throw error
            emit(UiState.Error((root ?: error).message ?: "Unable to load catalog"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val allTicketsState: StateFlow<UiState<List<SupportTicket>>> = repository.observeAllSupportTicketsAdmin()
        .map<List<SupportTicket>, UiState<List<SupportTicket>>> { UiState.Success(it) }
        .catch { error ->
            val root = extractFirestoreRootError(error)
            if (root == null && error is CancellationException) throw error
            emit(UiState.Error((root ?: error).message ?: "Unable to load support tickets"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val providerLogsState: StateFlow<UiState<List<ProviderLog>>> = repository.observeProviderLogsAdmin()
        .map<List<ProviderLog>, UiState<List<ProviderLog>>> { UiState.Success(it) }
        .catch { error ->
            val root = extractFirestoreRootError(error)
            if (root == null && error is CancellationException) throw error
            emit(UiState.Error((root ?: error).message ?: "Unable to load provider logs"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    init {
        viewModelScope.launch {
            platformConfig.collect { cfg ->
                backendClient.updateBaseUrl(cfg.backendBaseUrl)
                _activeBackendUrl.value = backendClient.activeBaseUrl
            }
        }
    }

    fun clearBanner() {
        _statusBanner.value = null
    }

    fun saveApiPlatformConfig(
        backendBaseUrl: String,
        clubKonnectMode: String,
        flutterwaveMode: String,
        virtualAccountsEnabled: Boolean,
        cardFundingEnabled: Boolean,
        maintenanceMode: Boolean
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            val current = platformConfig.value
            val result = repository.adminSavePlatformConfig(
                brandName = current.brandName,
                companyLegalName = current.companyLegalName,
                supportEmail = current.supportEmail,
                supportPhone = current.supportPhone,
                backendBaseUrl = backendBaseUrl,
                clubKonnectMode = clubKonnectMode,
                flutterwaveMode = flutterwaveMode,
                virtualAccountsEnabled = virtualAccountsEnabled,
                cardFundingEnabled = cardFundingEnabled,
                maintenanceMode = maintenanceMode
            )
            _isBusy.value = false
            result.onSuccess {
                backendClient.updateBaseUrl(backendBaseUrl)
                _activeBackendUrl.value = backendClient.activeBaseUrl
                _statusBanner.value = ActionBanner("Backend API & gateway configuration saved live.")
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Failed to save API configuration", isError = true)
            }
        }
    }

    fun seedDefaultServiceCatalog() {
        viewModelScope.launch {
            _isBusy.value = true
            val result = repository.adminSeedDefaultCatalog()
            _isBusy.value = false
            result.onSuccess { count ->
                _statusBanner.value = if (count == 0) {
                    ActionBanner("All default Nigerian utility services are already present in the catalog.")
                } else {
                    ActionBanner("Seeded $count Nigerian utility services into live catalog.")
                }
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Failed to seed catalog", isError = true)
            }
        }
    }

    fun syncLiveClubKonnectCatalog() {
        viewModelScope.launch {
            _isBusy.value = true
            val result = backendClient.adminSyncClubKonnectCatalog()
            _isBusy.value = false
            result.onSuccess { syncRes ->
                _statusBanner.value = ActionBanner(syncRes.message)
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Unable to sync ClubKonnect catalog", isError = true)
            }
        }
    }

    fun reconcilePendingTransaction(reference: String) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = backendClient.adminReconcileTransaction(reference)
            _isBusy.value = false
            result.onSuccess { resp ->
                _statusBanner.value = ActionBanner("Reconciled $reference → ${resp.status}: ${resp.message}")
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Reconciliation failed", isError = true)
            }
        }
    }

    fun updateServicePricing(
        serviceId: String,
        providerCostKobo: Long,
        sellingPriceKobo: Long,
        feeKobo: Long,
        discountPercent: Double,
        commissionPercent: Double,
        enabled: Boolean
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = repository.adminUpdateCatalogPricing(
                serviceId = serviceId,
                providerCostKobo = providerCostKobo,
                sellingPriceKobo = sellingPriceKobo,
                feeKobo = feeKobo,
                discountPercent = discountPercent,
                commissionPercent = commissionPercent,
                enabled = enabled
            )
            _isBusy.value = false
            result.onSuccess {
                _statusBanner.value = ActionBanner("Pricing configuration saved.")
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Failed to save pricing", isError = true)
            }
        }
    }

    fun updateCustomerStatus(targetUserId: String, status: AccountStatus, reason: String?) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = repository.adminUpdateCustomerStatus(targetUserId, status, reason)
            _isBusy.value = false
            result.onSuccess {
                _statusBanner.value = ActionBanner("Customer status updated to ${status.name}.")
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Failed to update account", isError = true)
            }
        }
    }

    fun respondToSupportTicket(ticketId: String, status: TicketStatus, response: String, notes: String?) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = repository.adminRespondToTicket(ticketId, status, response, notes)
            _isBusy.value = false
            result.onSuccess {
                _statusBanner.value = ActionBanner("Support ticket updated.")
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Failed to update ticket", isError = true)
            }
        }
    }

    fun checkProviderHealth() {
        viewModelScope.launch {
            _isBusy.value = true
            val result = backendClient.getProviderHealth()
            _isBusy.value = false
            result.onSuccess { health ->
                _providerHealth.value = health
                _statusBanner.value = ActionBanner(health.message)
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Backend health check failed", isError = true)
            }
        }
    }

    fun sendAnnouncementToUser(targetUserId: String, title: String, body: String) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = repository.adminSendNotification(targetUserId, title, body)
            _isBusy.value = false
            result.onSuccess {
                _statusBanner.value = ActionBanner("Notification dispatched.")
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Failed to send notification", isError = true)
            }
        }
    }

    private companion object {
        const val TAG = "AdminVM"
    }
}
