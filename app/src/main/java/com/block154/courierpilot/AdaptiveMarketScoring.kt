package com.block154.courierpilot

import kotlin.math.abs
import kotlin.math.pow

/** Currency-agnostic normalized sample consumed by the pure scoring engine. */
internal data class AdaptiveMarketSample(val ratePerKm: Double, val capturedAtEpochMs: Long, val cityKey: String, val currencyCode: String, val platform: String, val installationId: String? = null)
internal enum class MarketConfidence { NOT_READY, LOW, MEDIUM, HIGH }
internal enum class MarketProfileSource { PERSONAL, CITY, PERSONAL_AND_CITY, LEARNING }
internal data class AdaptiveMarketProfile(val sampleCount: Int, val effectiveSampleCount: Double, val median: Double, val p15: Double, val p35: Double, val p65: Double, val p85: Double, val confidence: MarketConfidence, val source: MarketProfileSource)
internal data class MarketScore(val band: OfferDecisionBand, val percentile: Double?, val confidence: MarketConfidence, val source: MarketProfileSource, val profile: AdaptiveMarketProfile?)

internal object AdaptiveMarketScoring {
 const val LIVE_WINDOW_DAYS=30L; const val HALF_LIFE_DAYS=10.0; const val PERSONAL_MIN_SAMPLES=5; const val PRIOR_STRENGTH=8.0
 fun recencyWeight(ageDays: Double)=if(!ageDays.isFinite()||ageDays<0||ageDays>30)0.0 else 0.5.pow(ageDays/10.0)
 fun effectiveSampleSize(w: List<Double>):Double { val x=w.filter{it.isFinite()&&it>0}; val s=x.sum(); val q=x.sumOf{it*it}; return if(q==0.0)0.0 else s*s/q }
 fun personalWeight(n:Double)=if(!n.isFinite()||n<=0)0.0 else n/(n+8.0)
 fun profile(os:List<AdaptiveMarketSample>,now:Long,source:MarketProfileSource=MarketProfileSource.PERSONAL):AdaptiveMarketProfile? { val a=os.mapNotNull{val w=recencyWeight((now-it.capturedAtEpochMs)/86400000.0); if(w>0&&it.ratePerKm.isFinite()&&it.ratePerKm>0)it to w else null}; if(a.isEmpty())return null; val s=robust(a); val v=s.map{it.first.ratePerKm}; val w=s.map{it.second}; return AdaptiveMarketProfile(v.size,effectiveSampleSize(w),q(v,w,.5),q(v,w,.15),q(v,w,.35),q(v,w,.65),q(v,w,.85),when{v.size<5->MarketConfidence.NOT_READY;v.size<10->MarketConfidence.LOW;v.size<25->MarketConfidence.MEDIUM;else->MarketConfidence.HIGH},source) }
 fun score(rate:Double,os:List<AdaptiveMarketSample>,now:Long,city:AdaptiveMarketProfile?=null):MarketScore { val p=profile(os,now); val personalReady = p != null && p.sampleCount >= PERSONAL_MIN_SAMPLES; val x=when { personalReady && city != null -> p!!; personalReady -> p!!; city != null -> city; p != null -> p; else -> return MarketScore(OfferDecisionBand.UNKNOWN,null,MarketConfidence.NOT_READY,MarketProfileSource.LEARNING,null) }; val pct=rank(rate,x); val source=when { personalReady && city != null -> MarketProfileSource.PERSONAL_AND_CITY; personalReady -> MarketProfileSource.PERSONAL; city != null -> MarketProfileSource.CITY; else -> MarketProfileSource.LEARNING }; return MarketScore(when{pct<.15->OfferDecisionBand.TERRIBLE;pct<.35->OfferDecisionBand.BAD;pct<.65->OfferDecisionBand.OK;pct<.85->OfferDecisionBand.GOOD;else->OfferDecisionBand.FIRE},pct,x.confidence,source,x) }
 private fun q(v:List<Double>,w:List<Double>,p:Double):Double { val i=v.indices.sortedBy{v[it]}; val t=i.sumOf{w[it]}; var c=0.0; for(k in i){c+=w[k];if(c>=t*p)return v[k]};return v[i.last()] }
 private fun rank(x:Double,p:AdaptiveMarketProfile)=when{ x<p.p15->0.0;x<p.p35->.15+.2*(x-p.p15)/(p.p35-p.p15).coerceAtLeast(1e-12);x<p.p65->.35+.3*(x-p.p35)/(p.p65-p.p35).coerceAtLeast(1e-12);x<p.p85->.65+.2*(x-p.p65)/(p.p85-p.p65).coerceAtLeast(1e-12);else->.85 }
 private fun robust(a:List<Pair<AdaptiveMarketSample,Double>>):List<Pair<AdaptiveMarketSample,Double>> { val v=a.map{it.first.ratePerKm}.sorted(); val m=v[v.size/2]; val d=v.map{abs(it-m)}.sorted()[v.size/2]; return if(d==0.0) a.filter{it.first.ratePerKm == m} else a.filter{abs(it.first.ratePerKm-m)<=6*d} }
}
