package com.example.binancephonescanner

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BinanceApiException(val code: Int, message: String) : Exception(message)

class Balance(val wallet: Double, val unrealized: Double, val available: Double) {
    val equity: Double get() = wallet + unrealized
}

class OrderResult(val orderId: Long, val executedQty: BigDecimal, val avgPrice: BigDecimal)

class SymbolRules(
    val tick: BigDecimal,
    val step: BigDecimal,
    val minQty: BigDecimal,
    val minNotional: Double
)

/**
 * Minimal Binance USDⓈ-M Futures client (REST). Every request that needs a signature is
 * signed with HMAC-SHA256. The API key and secret are only kept in memory here.
 */
class BinanceApi(
    private val baseUrl: String,
    private val apiKey: String,
    private val secret: String
) {
    companion object {
        @Volatile private var timeOffset = 0L
        @Volatile private var synced = false

        fun dec(text: String?): BigDecimal {
            return try {
                BigDecimal(text ?: "0")
            } catch (_: Exception) {
                BigDecimal.ZERO
            }
        }
    }

    private fun sign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        val out = StringBuilder()
        for (b in raw) {
            val v = b.toInt() and 0xff
            if (v < 16) out.append('0')
            out.append(Integer.toHexString(v))
        }
        return out.toString()
    }

    private fun requestOnce(method: String, path: String, params: Map<String, String>, signed: Boolean): String {
        val query = StringBuilder()
        for ((k, v) in params) {
            if (query.isNotEmpty()) query.append('&')
            query.append(k).append('=').append(URLEncoder.encode(v, "UTF-8"))
        }
        if (signed) {
            if (query.isNotEmpty()) query.append('&')
            query.append("recvWindow=10000&timestamp=").append(System.currentTimeMillis() + timeOffset)
            val signature = sign(query.toString())
            query.append("&signature=").append(signature)
        }
        val fullUrl = if (query.isNotEmpty()) "$baseUrl$path?$query" else "$baseUrl$path"
        val connection = URL(fullUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "BinanceAutoBuy/1.0")
            if (signed) connection.setRequestProperty("X-MBX-APIKEY", apiKey)
            if (method == "POST") {
                connection.doOutput = true
                connection.outputStream.use { }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                var apiCode = -code
                var message = "HTTP $code"
                try {
                    val o = JSONObject(body)
                    apiCode = o.optInt("code", apiCode)
                    message = o.optString("msg", message)
                } catch (_: Exception) {
                }
                throw BinanceApiException(apiCode, "$message ($apiCode)")
            }
            return body
        } finally {
            connection.disconnect()
        }
    }

    /** Matches the phone clock to the Binance server clock (a wrong clock makes signatures fail). */
    fun syncTime() {
        val body = requestOnce("GET", "/fapi/v1/time", emptyMap(), false)
        val serverTime = JSONObject(body).getLong("serverTime")
        timeOffset = serverTime - System.currentTimeMillis()
        synced = true
    }

    private fun request(method: String, path: String, params: Map<String, String>, signed: Boolean): String {
        if (signed && !synced) {
            try {
                syncTime()
            } catch (_: Exception) {
            }
        }
        try {
            return requestOnce(method, path, params, signed)
        } catch (e: BinanceApiException) {
            if (signed && e.code == -1021) {
                syncTime()
                return requestOnce(method, path, params, signed)
            }
            throw e
        }
    }

    // ---- Account ---------------------------------------------------------------------------

    fun balanceUsdt(): Balance {
        val array = JSONArray(request("GET", "/fapi/v2/balance", emptyMap(), true))
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            if (o.optString("asset") == "USDT") {
                return Balance(
                    o.optString("balance", "0").toDoubleOrNull() ?: 0.0,
                    o.optString("crossUnPnl", "0").toDoubleOrNull() ?: 0.0,
                    o.optString("availableBalance", "0").toDoubleOrNull() ?: 0.0
                )
            }
        }
        throw BinanceApiException(0, "USDT 잔고를 찾을 수 없습니다")
    }

    fun isHedgeMode(): Boolean {
        val o = JSONObject(request("GET", "/fapi/v1/positionSide/dual", emptyMap(), true))
        return o.optBoolean("dualSidePosition", false)
    }

    /** Amount of the long position of one symbol (0 when there is none). */
    fun positionAmt(symbol: String): BigDecimal {
        val array = JSONArray(request("GET", "/fapi/v2/positionRisk", mapOf("symbol" to symbol), true))
        var total = BigDecimal.ZERO
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val side = o.optString("positionSide", "BOTH")
            if (side == "BOTH" || side == "LONG") total = total.add(dec(o.optString("positionAmt", "0")))
        }
        return total
    }

    // ---- Trading ---------------------------------------------------------------------------

    fun setLeverage(symbol: String, leverage: Int) {
        request("POST", "/fapi/v1/leverage", mapOf("symbol" to symbol, "leverage" to leverage.toString()), true)
    }

    /** Cross margin keeps the account balance + unrealized profit a single number. */
    fun setCrossMargin(symbol: String) {
        request("POST", "/fapi/v1/marginType", mapOf("symbol" to symbol, "marginType" to "CROSSED"), true)
    }

    fun marketOrder(symbol: String, side: String, quantity: String, hedge: Boolean, reduceOnly: Boolean): OrderResult {
        val params = LinkedHashMap<String, String>()
        params["symbol"] = symbol
        params["side"] = side
        params["type"] = "MARKET"
        params["quantity"] = quantity
        params["newOrderRespType"] = "RESULT"
        if (hedge) {
            params["positionSide"] = "LONG"
        } else if (reduceOnly) {
            params["reduceOnly"] = "true"
        }
        val o = JSONObject(request("POST", "/fapi/v1/order", params, true))
        return OrderResult(
            o.optLong("orderId", 0L),
            dec(o.optString("executedQty", "0")),
            dec(o.optString("avgPrice", "0"))
        )
    }

    /** A stop-market order that closes the whole long position when the price falls to [triggerPrice]. */
    fun placeStop(symbol: String, triggerPrice: String, hedge: Boolean): Long {
        val params = LinkedHashMap<String, String>()
        params["algoType"] = "CONDITIONAL"
        params["symbol"] = symbol
        params["side"] = "SELL"
        params["type"] = "STOP_MARKET"
        params["triggerPrice"] = triggerPrice
        params["closePosition"] = "true"
        params["workingType"] = "CONTRACT_PRICE"
        if (hedge) params["positionSide"] = "LONG"
        val o = JSONObject(request("POST", "/fapi/v1/algoOrder", params, true))
        return o.optLong("algoId", 0L)
    }

    fun cancelAllStops(symbol: String) {
        request("DELETE", "/fapi/v1/algoOpenOrders", mapOf("symbol" to symbol), true)
    }

    // ---- Market data -----------------------------------------------------------------------

    fun exchangeRules(): Map<String, SymbolRules> {
        val root = JSONObject(request("GET", "/fapi/v1/exchangeInfo", emptyMap(), false))
        val symbols = root.getJSONArray("symbols")
        val result = HashMap<String, SymbolRules>()
        for (i in 0 until symbols.length()) {
            val item = symbols.getJSONObject(i)
            if (item.optString("contractType") != "PERPETUAL" || item.optString("quoteAsset") != "USDT") continue
            var tick = BigDecimal("0.01")
            var step = BigDecimal("1")
            var minQty = BigDecimal.ZERO
            var minNotional = 5.0
            var marketStep: BigDecimal? = null
            var marketMin: BigDecimal? = null
            val filters = item.getJSONArray("filters")
            for (j in 0 until filters.length()) {
                val f = filters.getJSONObject(j)
                val type = f.optString("filterType")
                if (type == "PRICE_FILTER") {
                    tick = dec(f.optString("tickSize", "0.01"))
                } else if (type == "LOT_SIZE") {
                    step = dec(f.optString("stepSize", "1"))
                    minQty = dec(f.optString("minQty", "0"))
                } else if (type == "MARKET_LOT_SIZE") {
                    marketStep = dec(f.optString("stepSize", "0"))
                    marketMin = dec(f.optString("minQty", "0"))
                } else if (type == "MIN_NOTIONAL") {
                    minNotional = f.optString("notional", "5").toDoubleOrNull() ?: 5.0
                }
            }
            val useStep = if (marketStep != null && marketStep.signum() > 0) marketStep else step
            val useMin = if (marketMin != null && marketMin.signum() > 0) marketMin else minQty
            result[item.getString("symbol")] = SymbolRules(tick, useStep, useMin, minNotional)
        }
        return result
    }
}
