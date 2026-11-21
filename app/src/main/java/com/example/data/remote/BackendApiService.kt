package com.example.data.remote

import com.example.BuildConfig
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.tasks.await
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class VirtualAccountRequest(
    val bvn: String? = null
)

@JsonClass(generateAdapter = true)
data class VirtualAccountResponse(
    val status: String,
    val message: String,
    val bankName: String? = null,
    val accountNumber: String? = null,
    val accountName: String? = null
)

@JsonClass(generateAdapter = true)
data class CardFundingRequest(
    val amountKobo: Long,
    val idempotencyKey: String
)

@JsonClass(generateAdapter = true)
data class CardFundingResponse(
    val status: String,
    val message: String,
    val reference: String? = null,
    val checkoutUrl: String? = null
)

@JsonClass(generateAdapter = true)
data class VerifyFundingRequest(
    val reference: String,
    val flutterwaveTransactionId: String? = null
)

@JsonClass(generateAdapter = true)
data class VerifyMeterRequest(
    val electricCompany: String,
    val meterNo: String,
    val meterType: String
)

@JsonClass(generateAdapter = true)
data class VerifySmartCardRequest(
    val cableTv: String,
    val smartCardNo: String
)

@JsonClass(generateAdapter = true)
data class CustomerVerificationResponse(
    val verified: Boolean,
    val customerName: String? = null,
    val customerAddress: String? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class PurchaseRequest(
    val idempotencyKey: String,
    val serviceType: String,
    val providerCode: String,
    val productCode: String,
    val recipient: String,
    val amountKobo: Long,
    val meterType: String? = null,
    val quantity: Int = 1,
    val transactionPin: String
)

@JsonClass(generateAdapter = true)
data class PurchaseResponse(
    val status: String,
    val transactionId: String? = null,
    val reference: String? = null,
    val message: String,
    val tokenOrPin: String? = null
)

@JsonClass(generateAdapter = true)
data class ProviderHealthResponse(
    val clubKonnectConfigured: Boolean,
    val clubKonnectBalanceNaira: String? = null,
    val flutterwaveConfigured: Boolean,
    val environment: String,
    val message: String
)

@JsonClass(generateAdapter = true)
data class CatalogSyncResponse(
    val status: String,
    val discoveredProducts: Int = 0,
    val updatedCatalogItems: Int = 0,
    val message: String
)

interface BackendApiService {
    @POST("api/v1/wallet/virtual-account")
    suspend fun requestVirtualAccount(
        @Header("Authorization") bearerToken: String,
        @Body request: VirtualAccountRequest
    ): Response<VirtualAccountResponse>

    @POST("api/v1/wallet/fund-card")
    suspend fun initiateCardFunding(
        @Header("Authorization") bearerToken: String,
        @Body request: CardFundingRequest
    ): Response<CardFundingResponse>

    @POST("api/v1/wallet/verify-funding")
    suspend fun verifyFunding(
        @Header("Authorization") bearerToken: String,
        @Body request: VerifyFundingRequest
    ): Response<PurchaseResponse>

    @POST("api/v1/verify/meter")
    suspend fun verifyElectricityMeter(
        @Header("Authorization") bearerToken: String,
        @Body request: VerifyMeterRequest
    ): Response<CustomerVerificationResponse>

    @POST("api/v1/verify/smartcard")
    suspend fun verifyCableSmartCard(
        @Header("Authorization") bearerToken: String,
        @Body request: VerifySmartCardRequest
    ): Response<CustomerVerificationResponse>

    @POST("api/v1/transactions/purchase")
    suspend fun submitPurchase(
        @Header("Authorization") bearerToken: String,
        @Body request: PurchaseRequest
    ): Response<PurchaseResponse>

    @POST("api/v1/transactions/{reference}/requery")
    suspend fun requeryTransaction(
        @Header("Authorization") bearerToken: String,
        @Path("reference") reference: String
    ): Response<PurchaseResponse>

    @GET("api/v1/admin/provider-health")
    suspend fun getProviderHealth(
        @Header("Authorization") bearerToken: String
    ): Response<ProviderHealthResponse>

    @POST("api/v1/admin/transactions/{reference}/reconcile")
    suspend fun adminReconcileTransaction(
        @Header("Authorization") bearerToken: String,
        @Path("reference") reference: String
    ): Response<PurchaseResponse>

    @POST("api/v1/admin/catalog/sync-clubkonnect")
    suspend fun adminSyncClubKonnectCatalog(
        @Header("Authorization") bearerToken: String
    ): Response<CatalogSyncResponse>
}

class TrustedBackendClient(
    initialBaseUrl: String = BuildConfig.BACKEND_BASE_URL
) {
    @Volatile
    private var currentBaseUrl: String = initialBaseUrl.trim()

    @Volatile
    private var cachedUrlForApi: String = ""

    @Volatile
    private var cachedApi: BackendApiService? = null

    val activeBaseUrl: String
        get() = currentBaseUrl

    val isConfigured: Boolean
        get() = isValidBackendUrl(currentBaseUrl)

    @Synchronized
    fun updateBaseUrl(newUrl: String?) {
        val candidate = newUrl?.trim().orEmpty().ifBlank { BuildConfig.BACKEND_BASE_URL.trim() }
        if (candidate != currentBaseUrl) {
            currentBaseUrl = candidate
            cachedApi = null
            cachedUrlForApi = ""
        }
    }

    @Synchronized
    private fun getOrBuildApi(): BackendApiService? {
        val url = currentBaseUrl
        if (!isValidBackendUrl(url)) return null
        if (cachedApi != null && cachedUrlForApi == url) return cachedApi

        val normalizedBase = if (url.endsWith("/")) url else "$url/"
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()
        val moshi = Moshi.Builder()
            .addLast(KotlinJsonAdapterFactory())
            .build()
        val built = Retrofit.Builder()
            .baseUrl(normalizedBase)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(BackendApiService::class.java)
        cachedApi = built
        cachedUrlForApi = url
        return built
    }

    private suspend fun requireBearerToken(): String {
        val user = Firebase.auth.currentUser
            ?: throw IllegalStateException("Please sign in with Google to authorize this request.")
        val token = user.getIdToken(false).await().token
            ?: throw IllegalStateException("Unable to retrieve Firebase ID token.")
        return "Bearer $token"
    }

    private fun requireApi(): BackendApiService =
        getOrBuildApi() ?: throw BackendNotDeployedException(
            "Trusted backend URL is not configured yet. Deploy /backend to Cloud Run and enter its HTTPS URL in Admin Operations → Providers & API (or set BACKEND_BASE_URL in app/build.gradle.kts)."
        )

    suspend fun requestVirtualAccount(bvn: String? = null): Result<VirtualAccountResponse> =
        executeCall { it.requestVirtualAccount(requireBearerToken(), VirtualAccountRequest(bvn)) }

    suspend fun initiateCardFunding(amountKobo: Long, idempotencyKey: String): Result<CardFundingResponse> =
        executeCall { it.initiateCardFunding(requireBearerToken(), CardFundingRequest(amountKobo, idempotencyKey)) }

    suspend fun verifyFunding(reference: String, flutterwaveTransactionId: String? = null): Result<PurchaseResponse> =
        executeCall { it.verifyFunding(requireBearerToken(), VerifyFundingRequest(reference, flutterwaveTransactionId)) }

    suspend fun verifyElectricityMeter(electricCompany: String, meterNo: String, meterType: String): Result<CustomerVerificationResponse> =
        executeCall { it.verifyElectricityMeter(requireBearerToken(), VerifyMeterRequest(electricCompany, meterNo, meterType)) }

    suspend fun verifyCableSmartCard(cableTv: String, smartCardNo: String): Result<CustomerVerificationResponse> =
        executeCall { it.verifyCableSmartCard(requireBearerToken(), VerifySmartCardRequest(cableTv, smartCardNo)) }

    suspend fun submitPurchase(request: PurchaseRequest): Result<PurchaseResponse> =
        executeCall { it.submitPurchase(requireBearerToken(), request) }

    suspend fun requeryTransaction(reference: String): Result<PurchaseResponse> =
        executeCall { it.requeryTransaction(requireBearerToken(), reference) }

    suspend fun getProviderHealth(): Result<ProviderHealthResponse> =
        executeCall { it.getProviderHealth(requireBearerToken()) }

    suspend fun adminReconcileTransaction(reference: String): Result<PurchaseResponse> =
        executeCall { it.adminReconcileTransaction(requireBearerToken(), reference) }

    suspend fun adminSyncClubKonnectCatalog(): Result<CatalogSyncResponse> =
        executeCall { it.adminSyncClubKonnectCatalog(requireBearerToken()) }

    private suspend fun <T> executeCall(block: suspend (BackendApiService) -> Response<T>): Result<T> {
        return try {
            val response = block(requireApi())
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Result.success(body)
            } else {
                val rawErr = response.errorBody()?.string()?.take(500).orEmpty()
                val parsedMessage = extractJsonErrorMessage(rawErr)
                Result.failure(
                    IllegalStateException(
                        parsedMessage ?: "Server returned HTTP ${response.code()}"
                    )
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        fun isValidBackendUrl(url: String): Boolean {
            val trimmed = url.trim()
            return trimmed.startsWith("https://") || trimmed.startsWith("http://10.0.2.2")
        }

        fun extractJsonErrorMessage(rawBody: String): String? {
            if (rawBody.isBlank()) return null
            return try {
                val obj = JSONObject(rawBody)
                obj.optString("message").takeIf { it.isNotBlank() }
                    ?: obj.optString("error").takeIf { it.isNotBlank() }
                    ?: rawBody.take(240)
            } catch (_: Exception) {
                rawBody.take(240)
            }
        }
    }
}

class BackendNotDeployedException(message: String) : IllegalStateException(message)
