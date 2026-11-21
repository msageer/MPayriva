package com.example.data.model

/**
 * Official Nigerian utility provider definitions aligned with ClubKonnect (Nellobyte Systems) API codes.
 * Actual customer pricing is managed centrally in Firestore /serviceCatalog and calculated by the backend.
 */
data class TelecomNetwork(
    val clubKonnectCode: String,
    val name: String,
    val shortCode: String
)

data class ElectricityDisco(
    val clubKonnectCode: String,
    val shortName: String,
    val fullName: String
)

data class CableProvider(
    val clubKonnectCode: String,
    val name: String
)

object NigerianProviders {
    // Official ClubKonnect MobileNetwork codes (APIAirtimeV1.asp & APIDatabundleV1.asp)
    val telecomNetworks = listOf(
        TelecomNetwork("01", "MTN Nigeria", "MTN"),
        TelecomNetwork("04", "Airtel Nigeria", "Airtel"),
        TelecomNetwork("02", "Globacom (Glo)", "Glo"),
        TelecomNetwork("03", "9mobile", "9mobile")
    )

    // Official Nigerian Electricity Distribution Companies (APIElectricityV1.asp)
    val electricityDiscos = listOf(
        ElectricityDisco("01", "EKEDC", "Eko Electricity Distribution"),
        ElectricityDisco("02", "IKEDC", "Ikeja Electric"),
        ElectricityDisco("03", "AEDC", "Abuja Electricity Distribution"),
        ElectricityDisco("04", "KEDCO", "Kano Electricity Distribution"),
        ElectricityDisco("05", "PHED", "Port Harcourt Electricity"),
        ElectricityDisco("06", "JED", "Jos Electricity Distribution"),
        ElectricityDisco("07", "IBEDC", "Ibadan Electricity Distribution"),
        ElectricityDisco("08", "KAEDCO", "Kaduna Electric"),
        ElectricityDisco("09", "EEDC", "Enugu Electricity Distribution"),
        ElectricityDisco("10", "BEDC", "Benin Electricity Distribution"),
        ElectricityDisco("11", "YEDC", "Yola Electricity Distribution")
    )

    // Official ClubKonnect Cable TV providers (APICableTVV1.asp)
    val cableProviders = listOf(
        CableProvider("dstv", "DStv"),
        CableProvider("gotv", "GOtv"),
        CableProvider("startimes", "StarTimes")
    )

    val businessCategories = listOf(
        "Telecoms & VTU Reseller",
        "POS & Agency Banking",
        "Retail & Supermarket",
        "Corporate / Enterprise",
        "ICT & Digital Services",
        "Logistics & E-Commerce",
        "Education & Cybercafe",
        "Other SME"
    )

    val rechargeCardDenominationsNaira = listOf(100L, 200L, 500L, 1000L)

    /**
     * Initial Nigerian utility catalog template used exclusively by Administrators
     * when bootstrapping /serviceCatalog in Firestore for the first time.
     * Prices are editable live in the Admin Pricing panel and never hardcoded in purchase execution.
     */
    fun defaultAdminSeedCatalog(adminUid: String): List<Pair<String, Map<String, Any>>> = listOf(
        // Airtime rules
        "airtime_mtn" to mapOf(
            "serviceType" to ServiceType.AIRTIME.name,
            "providerCode" to "01",
            "providerName" to "MTN",
            "productCode" to "mtn_airtime",
            "productName" to "MTN VTU Airtime",
            "providerCostKobo" to 9700L,
            "sellingPriceKobo" to 9900L,
            "feeKobo" to 0L,
            "discountPercent" to 1.0,
            "commissionPercent" to 2.0,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "airtime_airtel" to mapOf(
            "serviceType" to ServiceType.AIRTIME.name,
            "providerCode" to "04",
            "providerName" to "Airtel",
            "productCode" to "airtel_airtime",
            "productName" to "Airtel VTU Airtime",
            "providerCostKobo" to 9750L,
            "sellingPriceKobo" to 9900L,
            "feeKobo" to 0L,
            "discountPercent" to 1.0,
            "commissionPercent" to 1.5,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "airtime_glo" to mapOf(
            "serviceType" to ServiceType.AIRTIME.name,
            "providerCode" to "02",
            "providerName" to "Glo",
            "productCode" to "glo_airtime",
            "productName" to "Glo VTU Airtime",
            "providerCostKobo" to 9600L,
            "sellingPriceKobo" to 9850L,
            "feeKobo" to 0L,
            "discountPercent" to 1.5,
            "commissionPercent" to 2.5,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "airtime_9mobile" to mapOf(
            "serviceType" to ServiceType.AIRTIME.name,
            "providerCode" to "03",
            "providerName" to "9mobile",
            "productCode" to "9mobile_airtime",
            "productName" to "9mobile VTU Airtime",
            "providerCostKobo" to 9650L,
            "sellingPriceKobo" to 9850L,
            "feeKobo" to 0L,
            "discountPercent" to 1.5,
            "commissionPercent" to 2.0,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        // Data plans
        "data_mtn_1gb" to mapOf(
            "serviceType" to ServiceType.DATA.name,
            "providerCode" to "01",
            "providerName" to "MTN",
            "productCode" to "1000.0",
            "productName" to "MTN 1GB SME Data",
            "dataVolume" to "1GB",
            "validity" to "30 Days",
            "providerCostKobo" to 26000L,
            "sellingPriceKobo" to 29000L,
            "feeKobo" to 0L,
            "discountPercent" to 0.0,
            "commissionPercent" to 0.0,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "data_mtn_2gb" to mapOf(
            "serviceType" to ServiceType.DATA.name,
            "providerCode" to "01",
            "providerName" to "MTN",
            "productCode" to "2000.0",
            "productName" to "MTN 2GB SME Data",
            "dataVolume" to "2GB",
            "validity" to "30 Days",
            "providerCostKobo" to 52000L,
            "sellingPriceKobo" to 57000L,
            "feeKobo" to 0L,
            "discountPercent" to 0.0,
            "commissionPercent" to 0.0,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "data_mtn_5gb" to mapOf(
            "serviceType" to ServiceType.DATA.name,
            "providerCode" to "01",
            "providerName" to "MTN",
            "productCode" to "5000.0",
            "productName" to "MTN 5GB SME Data",
            "dataVolume" to "5GB",
            "validity" to "30 Days",
            "providerCostKobo" to 130000L,
            "sellingPriceKobo" to 142000L,
            "feeKobo" to 0L,
            "discountPercent" to 0.0,
            "commissionPercent" to 0.0,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "data_airtel_1gb" to mapOf(
            "serviceType" to ServiceType.DATA.name,
            "providerCode" to "04",
            "providerName" to "Airtel",
            "productCode" to "1000.0",
            "productName" to "Airtel 1GB Corporate Gifting",
            "dataVolume" to "1GB",
            "validity" to "30 Days",
            "providerCostKobo" to 28500L,
            "sellingPriceKobo" to 31000L,
            "feeKobo" to 0L,
            "discountPercent" to 0.0,
            "commissionPercent" to 0.0,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "data_glo_2gb" to mapOf(
            "serviceType" to ServiceType.DATA.name,
            "providerCode" to "02",
            "providerName" to "Glo",
            "productCode" to "2000.0",
            "productName" to "Glo 2GB Corporate Data",
            "dataVolume" to "2GB",
            "validity" to "30 Days",
            "providerCostKobo" to 50000L,
            "sellingPriceKobo" to 55000L,
            "feeKobo" to 0L,
            "discountPercent" to 0.0,
            "commissionPercent" to 0.0,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "data_9mobile_1gb" to mapOf(
            "serviceType" to ServiceType.DATA.name,
            "providerCode" to "03",
            "providerName" to "9mobile",
            "productCode" to "1000.0",
            "productName" to "9mobile 1GB Corporate",
            "dataVolume" to "1GB",
            "validity" to "30 Days",
            "providerCostKobo" to 22000L,
            "sellingPriceKobo" to 25000L,
            "feeKobo" to 0L,
            "discountPercent" to 0.0,
            "commissionPercent" to 0.0,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        // Electricity service fee config
        "electricity_standard" to mapOf(
            "serviceType" to ServiceType.ELECTRICITY.name,
            "providerCode" to "ALL_DISCOS",
            "providerName" to "Nigerian DisCos",
            "productCode" to "electricity_v1",
            "productName" to "Electricity Bill Payment (Prepaid/Postpaid)",
            "providerCostKobo" to 0L,
            "sellingPriceKobo" to 0L,
            "feeKobo" to 10000L,
            "discountPercent" to 0.0,
            "commissionPercent" to 1.0,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        // Cable TV packages
        "cable_dstv_padi" to mapOf(
            "serviceType" to ServiceType.CABLE_TV.name,
            "providerCode" to "dstv",
            "providerName" to "DStv",
            "productCode" to "dstv-padi",
            "productName" to "DStv Padi Bouquet",
            "validity" to "1 Month",
            "providerCostKobo" to 360000L,
            "sellingPriceKobo" to 360000L,
            "feeKobo" to 10000L,
            "discountPercent" to 0.0,
            "commissionPercent" to 1.2,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "cable_dstv_confam" to mapOf(
            "serviceType" to ServiceType.CABLE_TV.name,
            "providerCode" to "dstv",
            "providerName" to "DStv",
            "productCode" to "dstv-confam",
            "productName" to "DStv Confam Bouquet",
            "validity" to "1 Month",
            "providerCostKobo" to 930000L,
            "sellingPriceKobo" to 930000L,
            "feeKobo" to 10000L,
            "discountPercent" to 0.0,
            "commissionPercent" to 1.2,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "cable_gotv_jolli" to mapOf(
            "serviceType" to ServiceType.CABLE_TV.name,
            "providerCode" to "gotv",
            "providerName" to "GOtv",
            "productCode" to "gotv-jolli",
            "productName" to "GOtv Jolli Bouquet",
            "validity" to "1 Month",
            "providerCostKobo" to 485000L,
            "sellingPriceKobo" to 485000L,
            "feeKobo" to 10000L,
            "discountPercent" to 0.0,
            "commissionPercent" to 1.2,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "cable_startimes_basic" to mapOf(
            "serviceType" to ServiceType.CABLE_TV.name,
            "providerCode" to "startimes",
            "providerName" to "StarTimes",
            "productCode" to "basic",
            "productName" to "StarTimes Basic Monthly",
            "validity" to "1 Month",
            "providerCostKobo" to 330000L,
            "sellingPriceKobo" to 330000L,
            "feeKobo" to 10000L,
            "discountPercent" to 0.0,
            "commissionPercent" to 1.5,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        // Education PINs
        "edu_waec_result" to mapOf(
            "serviceType" to ServiceType.WAEC_PIN.name,
            "providerCode" to "waec",
            "providerName" to "WAEC",
            "productCode" to "waecdirect",
            "productName" to "WAEC Result Checker e-PIN",
            "providerCostKobo" to 345000L,
            "sellingPriceKobo" to 370000L,
            "feeKobo" to 0L,
            "discountPercent" to 0.0,
            "commissionPercent" to 0.0,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "edu_jamb_utme" to mapOf(
            "serviceType" to ServiceType.JAMB_PIN.name,
            "providerCode" to "jamb",
            "providerName" to "JAMB",
            "productCode" to "utme-mock",
            "productName" to "JAMB UTME e-PIN (With Mock)",
            "providerCostKobo" to 720000L,
            "sellingPriceKobo" to 750000L,
            "feeKobo" to 0L,
            "discountPercent" to 0.0,
            "commissionPercent" to 0.0,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        // Recharge Card Printing (e-PIN)
        "epin_mtn_100" to mapOf(
            "serviceType" to ServiceType.RECHARGE_CARD.name,
            "providerCode" to "01",
            "providerName" to "MTN",
            "productCode" to "100",
            "productName" to "MTN ₦100 Recharge Card PIN",
            "providerCostKobo" to 9650L,
            "sellingPriceKobo" to 9900L,
            "feeKobo" to 0L,
            "discountPercent" to 1.0,
            "commissionPercent" to 2.5,
            "enabled" to true,
            "updatedBy" to adminUid
        ),
        "epin_airtel_200" to mapOf(
            "serviceType" to ServiceType.RECHARGE_CARD.name,
            "providerCode" to "04",
            "providerName" to "Airtel",
            "productCode" to "200",
            "productName" to "Airtel ₦200 Recharge Card PIN",
            "providerCostKobo" to 19300L,
            "sellingPriceKobo" to 19800L,
            "feeKobo" to 0L,
            "discountPercent" to 1.0,
            "commissionPercent" to 2.5,
            "enabled" to true,
            "updatedBy" to adminUid
        )
    )
}
