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
import com.example.data.remote.AdminOverviewMetricsResponse
import com.example.data.remote.AdminPricingMatrixResponse
import com.example.data.remote.ClubKonnectFundingAccountDto
import com.example.data.remote.LiveFlutterwaveTransferRequest
import com.example.data.remote.NigerianBankDto
import com.example.data.remote.ProviderControlsResponse
import com.example.data.remote.ProviderHealthResponse
import com.example.data.remote.SavePlanOverrideRequest
import com.example.data.remote.ServicePricingRuleDto
import com.example.data.remote.TreasuryTransferDto
import com.example.data.remote.TrustedBackendClient
import com.example.data.remote.UpdateProviderControlsRequest
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

    private val _overviewMetrics = MutableStateFlow<AdminOverviewMetricsResponse?>(null)
    val overviewMetrics: StateFlow<AdminOverviewMetricsResponse?> = _overviewMetrics.asStateFlow()

    private val _pricingMatrix = MutableStateFlow<AdminPricingMatrixResponse?>(null)
    val pricingMatrix: StateFlow<AdminPricingMatrixResponse?> = _pricingMatrix.asStateFlow()

    private val _providerControls = MutableStateFlow<ProviderControlsResponse?>(null)
    val providerControls: StateFlow<ProviderControlsResponse?> = _providerControls.asStateFlow()

    private val _isLoadingPricing = MutableStateFlow(false)
    val isLoadingPricing: StateFlow<Boolean> = _isLoadingPricing.asStateFlow()

    private val _nigerianBanks = MutableStateFlow<List<NigerianBankDto>>(emptyList())
    val nigerianBanks: StateFlow<List<NigerianBankDto>> = _nigerianBanks.asStateFlow()

    private val _resolvedAccountName = MutableStateFlow<String?>(null)
    val resolvedAccountName: StateFlow<String?> = _resolvedAccountName.asStateFlow()

    private val _treasuryConfig = MutableStateFlow<ClubKonnectFundingAccountDto?>(null)
    val treasuryConfig: StateFlow<ClubKonnectFundingAccountDto?> = _treasuryConfig.asStateFlow()

    private val _treasuryTransfers = MutableStateFlow<List<TreasuryTransferDto>>(emptyList())
    val treasuryTransfers: StateFlow<List<TreasuryTransferDto>> = _treasuryTransfers.asStateFlow()

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

    fun syncAllPastFlutterwaveDeposits() {
        viewModelScope.launch {
            _isBusy.value = true
            val result = backendClient.adminSyncAllDeposits()
            _isBusy.value = false
            result.onSuccess { resp ->
                _statusBanner.value = ActionBanner(resp.message)
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Unable to sync Flutterwave deposits", isError = true)
            }
        }
    }

    fun fundAdminWalletFromMainBalance(amountKobo: Long) {
        if (amountKobo < 10000L) {
            _statusBanner.value = ActionBanner("Minimum transfer from main balance is ₦100.00.", isError = true)
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val idempotencyKey = "admin_main_${UUID.randomUUID()}"
            val result = backendClient.adminFundFromMainBalance(amountKobo, idempotencyKey)
            _isBusy.value = false
            result.onSuccess { resp ->
                _statusBanner.value = ActionBanner(resp.message)
                backendClient.getProviderHealth().onSuccess { _providerHealth.value = it }
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Unable to draw from Flutterwave main balance", isError = true)
            }
        }
    }

    fun fundAdminWalletFromClubKonnect(amountKobo: Long) {
        if (amountKobo < 10000L) {
            _statusBanner.value = ActionBanner("Minimum draw from ClubKonnect balance is ₦100.00.", isError = true)
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val idempotencyKey = "admin_ck_${UUID.randomUUID()}"
            val result = backendClient.adminFundFromClubKonnect(amountKobo, idempotencyKey)
            _isBusy.value = false
            result.onSuccess { resp ->
                _statusBanner.value = ActionBanner(resp.message)
                backendClient.getProviderHealth().onSuccess { _providerHealth.value = it }
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Unable to load from ClubKonnect balance", isError = true)
            }
        }
    }

    fun transferClubKonnectToMainAccount(amountKobo: Long) {
        if (amountKobo < 10000L) {
            _statusBanner.value = ActionBanner("Minimum transfer amount is ₦100.00.", isError = true)
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val idempotencyKey = "admin_ck2main_${UUID.randomUUID()}"
            val result = backendClient.adminTransferClubKonnectToMain(amountKobo, idempotencyKey)
            _isBusy.value = false
            result.onSuccess { resp ->
                _statusBanner.value = ActionBanner(resp.message)
                backendClient.getProviderHealth().onSuccess { _providerHealth.value = it }
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Unable to transfer ClubKonnect balance", isError = true)
            }
        }
    }

    fun toggleUtilityService(serviceKeys: List<String>, enabled: Boolean, displayLabel: String) {
        viewModelScope.launch {
            _isBusy.value = true
            val current = platformConfig.value
            val updatedMap = current.enabledServices.toMutableMap()
            serviceKeys.forEach { key -> updatedMap[key] = enabled }
            val result = repository.adminSaveServiceToggles(current, updatedMap)
            _isBusy.value = false
            result.onSuccess {
                _statusBanner.value = ActionBanner("$displayLabel ${if (enabled) "enabled" else "disabled"} on storefront.")
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Failed to update service toggle", isError = true)
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

    fun loadOverviewMetrics(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _isBusy.value = true
            val result = backendClient.adminGetOverviewMetrics()
            if (!silent) _isBusy.value = false
            result.onSuccess { metrics ->
                _overviewMetrics.value = metrics
                if (!silent) {
                    _statusBanner.value = ActionBanner("Live executive overview & provider float refreshed.")
                }
            }.onFailure { err ->
                if (!silent) {
                    _statusBanner.value = ActionBanner(err.message ?: "Unable to fetch live overview metrics", isError = true)
                }
            }
        }
    }

    fun reconcileAllPendingTransactions() {
        viewModelScope.launch {
            _isBusy.value = true
            val result = backendClient.adminReconcileAllPending()
            _isBusy.value = false
            result.onSuccess { resp ->
                _statusBanner.value = ActionBanner(resp.message)
                loadOverviewMetrics(silent = true)
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Batch reconciliation failed", isError = true)
            }
        }
    }

    fun loadPricingMatrix(refresh: Boolean = false, silent: Boolean = false) {
        viewModelScope.launch {
            _isLoadingPricing.value = true
            val result = backendClient.adminGetPricingMatrix(refresh)
            _isLoadingPricing.value = false
            result.onSuccess { matrix ->
                _pricingMatrix.value = matrix
                if (matrix.providersSummary.isNotEmpty() || matrix.serviceRouting.isNotEmpty()) {
                    _providerControls.value = ProviderControlsResponse(
                        status = "success",
                        providers = matrix.providersSummary,
                        serviceRouting = matrix.serviceRouting
                    )
                }
                if (!silent && refresh) {
                    _statusBanner.value = ActionBanner("Fetched ${matrix.totalPlans} live plans across active providers.")
                }
            }.onFailure { err ->
                if (!silent) {
                    _statusBanner.value = ActionBanner(err.message ?: "Unable to fetch live pricing matrix", isError = true)
                }
            }
        }
    }

    fun loadProviderControls(silent: Boolean = true) {
        viewModelScope.launch {
            if (!silent) _isBusy.value = true
            val result = backendClient.adminGetProviderControls()
            if (!silent) _isBusy.value = false
            result.onSuccess { controls ->
                _providerControls.value = controls
            }.onFailure { err ->
                if (!silent) {
                    _statusBanner.value = ActionBanner(err.message ?: "Unable to load provider controls", isError = true)
                }
            }
        }
    }

    fun toggleApiProvider(providerId: String, enabled: Boolean) {
        viewModelScope.launch {
            _isBusy.value = true
            val req = when (providerId.uppercase()) {
                "CLUBKONNECT" -> UpdateProviderControlsRequest(clubKonnectEnabled = enabled)
                "ALRAHUZDATA" -> UpdateProviderControlsRequest(alrahuzDataEnabled = enabled)
                else -> UpdateProviderControlsRequest()
            }
            val result = backendClient.adminUpdateProviderControls(req)
            _isBusy.value = false
            result.onSuccess { updated ->
                _providerControls.value = updated
                _statusBanner.value = ActionBanner(
                    updated.message ?: "$providerId ${if (enabled) "enabled" else "disabled"} live."
                )
                loadPricingMatrix(refresh = true, silent = true)
                loadOverviewMetrics(silent = true)
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Failed to update provider toggle", isError = true)
            }
        }
    }

    fun updateServiceRouting(serviceType: String, routingMode: String) {
        viewModelScope.launch {
            _isBusy.value = true
            val req = UpdateProviderControlsRequest(
                serviceRouting = mapOf(serviceType.uppercase() to routingMode.uppercase())
            )
            val result = backendClient.adminUpdateProviderControls(req)
            _isBusy.value = false
            result.onSuccess { updated ->
                _providerControls.value = updated
                _statusBanner.value = ActionBanner("Updated $serviceType routing to $routingMode.")
                loadPricingMatrix(refresh = true, silent = true)
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Failed to update service routing", isError = true)
            }
        }
    }

    fun savePricingRules(rules: List<ServicePricingRuleDto>) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = backendClient.adminSavePricingRules(rules)
            _isBusy.value = false
            result.onSuccess { updated ->
                _pricingMatrix.value = updated
                _statusBanner.value = ActionBanner(updated.message ?: "Pricing rules saved & live prices recalculated.")
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Failed to save pricing rules", isError = true)
            }
        }
    }

    fun savePlanOverride(request: SavePlanOverrideRequest) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = backendClient.adminSavePlanOverride(request)
            _isBusy.value = false
            result.onSuccess { resp ->
                _statusBanner.value = ActionBanner(resp.message)
                loadPricingMatrix(refresh = true, silent = true)
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Failed to save plan override", isError = true)
            }
        }
    }

    fun loadTreasuryWorkspace(silent: Boolean = true) {
        viewModelScope.launch {
            if (!silent) _isBusy.value = true
            backendClient.getProviderHealth().onSuccess { _providerHealth.value = it }
            backendClient.adminGetTreasuryConfig().onSuccess { cfg ->
                _treasuryConfig.value = cfg.clubKonnectFundingAccount
                _treasuryTransfers.value = cfg.recentTransfers
            }
            if (_nigerianBanks.value.isEmpty()) {
                backendClient.adminListNigerianBanks().onSuccess { resp ->
                    _nigerianBanks.value = resp.banks
                }
            }
            if (!silent) _isBusy.value = false
        }
    }

    fun clearResolvedAccount() {
        _resolvedAccountName.value = null
    }

    fun resolveBankAccount(bankCode: String, accountNumber: String) {
        val cleanAcct = accountNumber.filter { it.isDigit() }
        if (bankCode.isBlank() || cleanAcct.length != 10) {
            _statusBanner.value = ActionBanner("Select a bank and enter a 10-digit account number first.", isError = true)
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val result = backendClient.adminResolveBankAccount(bankCode, cleanAcct)
            _isBusy.value = false
            result.onSuccess { resp ->
                _resolvedAccountName.value = resp.accountName
                _statusBanner.value = ActionBanner("Verified: ${resp.accountName}")
            }.onFailure { err ->
                _resolvedAccountName.value = null
                _statusBanner.value = ActionBanner(err.message ?: "Unable to verify bank account", isError = true)
            }
        }
    }

    fun saveClubKonnectFundingAccount(
        bankCode: String,
        bankName: String,
        accountNumber: String,
        accountName: String
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            val result = backendClient.adminSaveTreasuryConfig(
                ClubKonnectFundingAccountDto(
                    bankCode = bankCode,
                    bankName = bankName,
                    accountNumber = accountNumber.filter { it.isDigit() },
                    accountName = accountName.trim()
                )
            )
            _isBusy.value = false
            result.onSuccess { resp ->
                _treasuryConfig.value = resp.clubKonnectFundingAccount
                _statusBanner.value = ActionBanner(resp.message)
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Failed to save ClubKonnect funding account", isError = true)
            }
        }
    }

    fun initiateLiveFlutterwaveToClubKonnectTransfer(
        amountKobo: Long,
        bankCode: String,
        bankName: String,
        accountNumber: String,
        accountName: String,
        narration: String
    ) {
        if (amountKobo < 10000L) {
            _statusBanner.value = ActionBanner("Minimum live transfer is ₦100.00.", isError = true)
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            val request = LiveFlutterwaveTransferRequest(
                amountKobo = amountKobo,
                bankCode = bankCode,
                bankName = bankName,
                accountNumber = accountNumber.filter { it.isDigit() },
                accountName = accountName.trim(),
                narration = narration.trim().ifBlank { "M-Sageer Pay ClubKonnect Float Top-Up" },
                idempotencyKey = "flw2ck_${UUID.randomUUID()}"
            )
            val result = backendClient.adminInitiateFlutterwaveToClubKonnectTransfer(request)
            _isBusy.value = false
            result.onSuccess { resp ->
                _statusBanner.value = ActionBanner(resp.message)
                loadTreasuryWorkspace(silent = true)
                loadOverviewMetrics(silent = true)
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Live Flutterwave transfer failed", isError = true)
            }
        }
    }

    fun checkLiveTransferStatus(transferId: Long) {
        if (transferId <= 0L) return
        viewModelScope.launch {
            _isBusy.value = true
            val result = backendClient.adminCheckTransferStatus(transferId.toString())
            _isBusy.value = false
            result.onSuccess { resp ->
                _statusBanner.value = ActionBanner(resp.message)
                loadTreasuryWorkspace(silent = true)
            }.onFailure { err ->
                _statusBanner.value = ActionBanner(err.message ?: "Unable to query transfer status", isError = true)
            }
        }
    }

    private companion object {
        const val TAG = "AdminVM"
    }
}
