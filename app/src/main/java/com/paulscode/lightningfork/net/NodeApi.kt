package com.paulscode.lightningfork.net

import kotlinx.serialization.KSerializer
import java.util.UUID

/** The mobile API's calls, typed. */
class NodeApi(private val transport: Transport) {
    private suspend fun <T> getJson(path: String, ser: KSerializer<T>, timeout: Long = 30): T =
        ApiJson.decodeFromString(ser, transport.get(path, timeout))

    private suspend fun <B, T> postJson(
        path: String,
        bodySer: KSerializer<B>,
        body: B,
        ser: KSerializer<T>,
        timeout: Long = 30,
        auth: Boolean = true,
        headers: Map<String, String> = emptyMap(),
    ): T = ApiJson.decodeFromString(
        ser,
        transport.post(path, ApiJson.encodeToString(bodySer, body), timeout, auth, extraHeaders = headers),
    )

    suspend fun pair(req: PairRequest): PairResponse =
        postJson("/api/v1/pair", PairRequest.serializer(), req, PairResponse.serializer(), auth = false)

    /** Removes this phone on the node; [key] given, as after the phone forgot it. */
    suspend fun unpair(key: String) {
        transport.post("/api/v1/unpair", "{}", timeoutSeconds = 20, key = key)
    }

    suspend fun bootstrap(): BootstrapResponse = getJson("/api/v1/bootstrap", BootstrapResponse.serializer())

    suspend fun endpoints(): EndpointsResponse = getJson("/api/v1/endpoints", EndpointsResponse.serializer())

    suspend fun wallet(): WalletResponse = getJson("/api/v1/wallet", WalletResponse.serializer())

    suspend fun fees(): FeesResponse = getJson("/api/v1/fees", FeesResponse.serializer())

    /**
     * Says which kinds beyond the first ones this app can show: without it,
     * the node refuses a SHA256 invoice rather than describe one. A SHA256
     * invoice's price is asked of the service, over Tor when it is an
     * onion, with the market rate beside it: give it a minute.
     */
    suspend fun decode(input: String): PaymentTarget =
        postJson(
            "/api/v1/decode",
            DecodeRequest.serializer(),
            DecodeRequest(input),
            PaymentTarget.serializer(),
            timeout = 60,
            headers = mapOf(CAPABILITIES_HEADER to CAPABILITIES),
        )

    /** The service for SHA256 invoices and its terms now; asks the service, so it can take a while. */
    suspend fun bitcoinInvoices(): BitcoinInvoicesStatus =
        getJson("/api/v1/bitcoin-invoices", BitcoinInvoicesStatus.serializer(), timeout = 60)

    suspend fun estimateOnchain(req: OnchainEstimateRequest): OnchainEstimate =
        postJson("/api/v1/onchain/estimate", OnchainEstimateRequest.serializer(), req, OnchainEstimate.serializer())

    suspend fun sendOnchain(req: OnchainSendRequest): OnchainSendResponse =
        postJson("/api/v1/onchain/send", OnchainSendRequest.serializer(), req, OnchainSendResponse.serializer(), timeout = 60)

    /** A payment can take a while to find its route; wait up to two minutes. */
    suspend fun pay(req: PayRequest): PayResponse =
        postJson("/api/v1/lightning/pay", PayRequest.serializer(), req, PayResponse.serializer(), timeout = 120)

    /**
     * The service holds the payment until it has paid the SHA256 invoice;
     * the node answers "on its way" after a minute and a half, having first
     * asked for the market rate and the service's request (up to about 75
     * seconds more).
     */
    suspend fun payBitcoinInvoice(req: BitcoinInvoicePayRequest): PayResponse =
        postJson("/api/v1/pay/bitcoin-invoice", BitcoinInvoicePayRequest.serializer(), req, PayResponse.serializer(), timeout = 200)

    suspend fun address(fresh: Boolean = false): AddressResponse =
        postJson("/api/v1/receive/address", AddressRequest.serializer(), AddressRequest(fresh), AddressResponse.serializer())

    suspend fun createInvoice(req: InvoiceRequest): InvoiceResponse =
        postJson("/api/v1/receive/invoice", InvoiceRequest.serializer(), req, InvoiceResponse.serializer())

    suspend fun invoiceStatus(paymentHash: String): InvoiceStatus =
        getJson("/api/v1/receive/invoice/$paymentHash", InvoiceStatus.serializer())

    suspend fun activity(limit: Int = 50): ActivityResponse =
        getJson("/api/v1/activity?limit=$limit", ActivityResponse.serializer())

    /** The currencies the node can quote now; dollars only from a dashboard too old to say. */
    suspend fun currencies(): List<String> =
        try {
            ApiJson.decodeFromString(CurrenciesResponse.serializer(), transport.get("/api/v1/currencies", 30)).currencies
        } catch (e: ApiException) {
            if (e.status == 404) listOf("USD") else throw e
        }

    suspend fun price(currency: String = "USD"): PriceResponse =
        getJson("/api/v1/price?currency=$currency", PriceResponse.serializer())

    companion object {
        const val CAPABILITIES_HEADER = "X-LF-Capabilities"
        const val CAPABILITIES = "bitcoin-invoice"

        /** A fresh id for one attempt at moving money. */
        fun newRequestId(): String = UUID.randomUUID().toString()
    }
}
