package com.block154.courierpilot

data class MarketObservation(val offerId: Long, val capturedAt: Long, val cityKey: String, val cityName: String?, val countryCode: String?, val platform: String, val money: MoneyAmount, val fullRouteDistanceMeters: Int, val routeSource: String, val deliveryCount: Int?, val localHour: Int?, val localWeekday: Int?, val uploadedAt: Long? = null, val syncState: String = "PENDING")
enum class MarketObservationBucket { DAY, WEEK, MONTH }
