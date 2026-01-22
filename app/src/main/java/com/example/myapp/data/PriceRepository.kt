package com.example.myapp.data

import android.util.Log
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.google.gson.Gson
import com.example.myapp.ENABLE_EXCHANGE_LOGS

class PriceRepository {
    private val binanceRetrofit = Retrofit.Builder()
        .baseUrl("https://testnet.binance.vision/")
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .build()
    
    private val coinbaseRetrofit = Retrofit.Builder()
        .baseUrl("https://api.coinbase.com/")
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .build()
    
    // Coinbase Exchange API for order book
    private val coinbaseExchangeRetrofit = Retrofit.Builder()
        .baseUrl("https://api.exchange.coinbase.com/")
        .addConverterFactory(GsonConverterFactory.create(Gson()))
        .build()
    
    private val binanceApi = binanceRetrofit.create(CryptoApiService::class.java)
    private val coinbaseApi = coinbaseRetrofit.create(CryptoApiService::class.java)
    private val coinbaseExchangeApi = coinbaseExchangeRetrofit.create(CryptoApiService::class.java)
    
    suspend fun getBinancePrice(): Result<Double> {
        return try {
            val response = binanceApi.getBinancePrice()
            Result.success(response.price.toDouble())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getCoinbasePrice(): Result<Double> {
        return try {
            val response = coinbaseApi.getCoinbasePrice()
            Result.success(response.data.amount.toDouble())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    suspend fun getBinanceVolumes(): Result<Pair<Double, Double>> {
        return try {
            val trades = binanceApi.getBinanceTrades("BTCUSDT", 100)
            var buyVolume = 0.0
            var sellVolume = 0.0
            
            trades.forEach { trade ->
                val qty = trade.qty.toDoubleOrNull() ?: 0.0
                if (trade.isBuyerMaker) {
                    // isBuyerMaker = true means seller is maker, so this is a SELL
                    sellVolume += qty
                } else {
                    // isBuyerMaker = false means buyer is taker, so this is a BUY
                    buyVolume += qty
                }
            }
            
            if (ENABLE_EXCHANGE_LOGS) {
                Log.d("PriceRepository", "Binance Volumes - Buy: $buyVolume, Sell: $sellVolume, Total Trades: ${trades.size}")
            }
            
            Result.success(Pair(buyVolume, sellVolume))
        } catch (e: Exception) {
            if (ENABLE_EXCHANGE_LOGS) {
                Log.e("PriceRepository", "Error fetching Binance volumes: ${e.message}", e)
            }
            Result.failure(e)
        }
    }
    
    suspend fun getCoinbaseVolumes(): Result<Pair<Double, Double>> {
        return try {
            // Try Coinbase Exchange API first for order book
            val orderBook = coinbaseExchangeApi.getCoinbaseOrderBook()
            
            var buyVolume = 0.0
            var sellVolume = 0.0
            
            // Sum up bid volumes (buy side) - take top 50 levels
            orderBook.bids.take(50).forEach { bid ->
                val size = bid.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                buyVolume += size
            }
            
            // Sum up ask volumes (sell side) - take top 50 levels
            orderBook.asks.take(50).forEach { ask ->
                val size = ask.getOrNull(1)?.toDoubleOrNull() ?: 0.0
                sellVolume += size
            }
            
            if (ENABLE_EXCHANGE_LOGS) {
                Log.d("PriceRepository", "Coinbase Volumes - Buy: $buyVolume, Sell: $sellVolume, Bids: ${orderBook.bids.size}, Asks: ${orderBook.asks.size}")
            }
            
            Result.success(Pair(buyVolume, sellVolume))
        } catch (e: Exception) {
            if (ENABLE_EXCHANGE_LOGS) {
                Log.e("PriceRepository", "Error fetching Coinbase volumes: ${e.message}", e)
            }
            // Fallback: return small default values so bars show up
            // This prevents null volumes which cause grey bars
            val fallback = Pair(0.01, 0.01)
            if (ENABLE_EXCHANGE_LOGS) {
                Log.w("PriceRepository", "Using fallback Coinbase volumes: $fallback")
            }
            Result.success(fallback)
        }
    }
}
