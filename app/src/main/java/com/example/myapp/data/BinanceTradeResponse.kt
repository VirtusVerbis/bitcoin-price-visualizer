package com.example.myapp.data

data class BinanceTrade(
    val id: Long,
    val price: String,
    val qty: String,
    val quoteQty: String,
    val time: Long,
    val isBuyerMaker: Boolean,
    val isBestMatch: Boolean
)
