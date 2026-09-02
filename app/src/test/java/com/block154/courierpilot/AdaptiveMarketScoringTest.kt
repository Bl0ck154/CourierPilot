package com.block154.courierpilot
import org.junit.Assert.*; import org.junit.Test
class AdaptiveMarketScoringTest { private fun o(r:Double)=AdaptiveMarketSample(r,99*86400000L,"x","EUR","Wolt")
 @Test fun fifthSampleActivates(){val n=100*86400000L;assertEquals(MarketConfidence.NOT_READY,AdaptiveMarketScoring.score(2.0,(1..4).map{ o(it.toDouble())},n).confidence);assertEquals(MarketConfidence.LOW,AdaptiveMarketScoring.score(2.0,(1..5).map{ o(it.toDouble())},n).confidence)}
 @Test fun scaleInvariant(){val n=100*86400000L;assertEquals(AdaptiveMarketScoring.score(7.0,(1..10).map{ o(it.toDouble())},n).band,AdaptiveMarketScoring.score(700.0,(1..10).map{o(it*100.0)},n).band)}
 @Test fun candidateIsNotInsertedIntoItsOwnReferenceProfile(){val n=100*86400000L;val reference=(1..5).map{o(it.toDouble())};val result=AdaptiveMarketScoring.score(10.0,reference,n);assertEquals(5,result.profile?.sampleCount);assertEquals(5,reference.size)} }
