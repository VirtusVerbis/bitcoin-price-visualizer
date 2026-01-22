package com.example.myapp.data

import retrofit2.http.GET
import retrofit2.http.Query

interface CryptoApiService {
    @GET("api/v3/ticker/price?symbol=BTCUSDT")
    suspend fun getBinancePrice(): BinancePriceResponse
    
    @GET("api/v3/trades")
    suspend fun getBinanceTrades(
        @Query("symbol") symbol: String = "BTCUSDT",
        @Query("limit") limit: Int = 100
    ): List<BinanceTrade>
    
    @GET("v2/prices/BTC-USD/spot")
    suspend fun getCoinbasePrice(): CoinbasePriceResponse
    
    @GET("products/BTC-USD/book")
    suspend fun getCoinbaseOrderBook(): CoinbaseOrderBookResponse
}
