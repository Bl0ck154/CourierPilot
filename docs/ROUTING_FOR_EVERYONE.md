# Routing without a shared secret: options and public beta plan

Research and production snapshot: **2026-11-02**. No public routing endpoint, external demo, or third-party tracking was enabled as part of this research.

## What every CourierPilot install already has

CourierPilot archives offers and can show a **provisional** payout / platform-reported km when Wolt or Bolt includes both values. It does not need an account or Valhalla. These estimates are not route-verified and must never feed Valhalla-based city scoring.

Version 0.17.12 also groups these provisional offers into separate **local-only 90-day personal trends** by selected platform/currency and Day/Week/Month, excluding Wolt incremental add-ons. No coordinates or samples are uploaded, no inferred distance is invented for a Bolt offer without distance, and no coloured verified-route verdict is derived from these rates.

## Product priority: international from day one

**The initial Lithuania-only route graph is an infrastructure legacy, not the target market.** CourierPilot should work for couriers across Europe and ultimately other supported regions. Prioritize usable coverage in **Germany and Poland** while retaining Lithuania; do not publish an "Europe supported" badge until the actual routed map regions have been tested.

1. **Immediate international pilot:** build a provider adapter and explicit opt-in to a tightly capped public worldwide demo after notifying its maintainers. No automatic background bulk use; fall back to local platform-distance estimates.
2. **Production path:** launch a dedicated, isolated routing server with a protected edge gateway, rotating per-install credentials, consent, quotas and reliable multi-country map coverage. Try an eligible Oracle Always Free VM for a bounded pilot, but plan paid backup capacity if it cannot provision or scale.
3. **Long-term low-cost path:** evaluate BRouter offline regions with user-controlled map downloads and no location transmission; do not bundle Europe-wide graphs into the APK.

"Install APK and forget it" is the user-experience goal. Provider selection and keys should be handled by CourierPilot's own backend rather than users copying private credentials. No public production server has yet been provisioned.

## Routing providers

| Option | Global coverage | Keys / offline | Suitable default? |
|---|---|---|---|
| **FOSSGIS public Valhalla demo** | Full planet | No key; sends coordinates to FOSSGIS | **Only explicit opt-in bounded beta**, after notifying maintainers, with X-Client-Id and fair use. No guaranteed service |
| **BRouter Android** | Areas downloaded by user | Offline data and separate routing engine/app integration | Promising eventual optional offline route provider, not a 1-click ready library |
| **openrouteservice / HeiGIT** | Multi-country | API key, separate protocol; Standard plan directions approx. 2,000 requests/day (check current plan) | Backend-owned key and strict limits for a small pilot |
| **GraphHopper Directions API** | Global/plan-dependent | API key; free 500 credits/day and non-commercial restrictions | Demo and evaluation only, not general unlimited distribution |
| **OSRM public demo** | Multiple profiles | Public API; strict fair use, 1 req/s and no uptime guarantee | Never automatic mass-market fallback |
| **Self-hosted Valhalla** | Only imported OSM regions | Private HTTPS gateway with protected token | Controlled production beta after capacity and abuse controls |

Primary sources:
- https://valhalla.github.io/valhalla/start/introduction/
- https://github.com/valhalla/valhalla/blob/master/docs/docs/api/openapi.yaml
- https://github.com/abrensch/brouter
- https://openrouteservice.org/plans/
- https://ask.openrouteservice.org/t/reducing-the-quota-of-deprecated-api-api-openrouteservice-org/8013
- https://www.graphhopper.com/pricing/
- https://github.com/Project-OSRM/osrm-backend/wiki/Api-usage-policy

### Why a public demo cannot silently ship in the APK

FOSSGIS asks mobile/web developers to notify them through Valhalla GitHub Discussions and identify requests with `X-Client-Id: CourierPilot`. Rate limits are per-source and global. At 100 couriers × 30 offers/day × 2 routing profiles, a **hypothetical** daily total is **6,000 route requests**, with much higher lunchtime burst rates. At 500 couriers × 50 offers × 2 profiles, it is **50,000/day**. These are scenarios, not measured traffic.

The current Android `RouteEndpointConfig` requires a valid HTTPS origin and private bearer token. To integrate a no-key demo, add a **distinct provider abstraction**, not a magic string or fake token that disables authentication on the private transport. The future opt-in UI must disclose that live GPS/stop coordinates are sent to an outside organisation; the user may decline without loss of local offer capture. It must apply global/per-install limits, use `X-Client-Id`, handle 429/retry, and fall back to platform-only estimates. No uncontrolled background requests to a demo.

## Current routing coverage and readiness

The private self-hosted Valhalla currently has **Lithuania-only** map extracts. It is not a worldwide routing service. Before a public beta it needs measured capacity under realistic load, strict resource budgets, coverage checks, abuse mitigation and operational isolation. Private server metrics, addresses, detailed gateway thresholds and storage headroom should stay out of this public repository; they are reviewed in owner-only operations reports.

A short authenticated local smoke test succeeded, but a successful route calculation is not a capacity benchmark. Do not enable public requests on the production origin without a rate-limited edge gateway and kill switch.

## How to launch an international beta safely

1. **Do not bake a server secret into the APK**. Keep current privileged upstream token behind localhost/Cloudflare/nginx.
2. Host a distinct public beta hostname (or `/beta/route`) at an edge gateway. Enrol clients with revocable per-install short-lived tokens; unauthenticated self-minted tokens alone cannot prevent Sybil abuse, so pair with IP and global budgets.
3. Restrict to `POST /route`, JSON content type, bounded request size, at most e.g. 6 ordered waypoints, only bicycle/pedestrian costings, coordinate validation, no transit/isoline/matrix endpoints. Validate **every** waypoint is within supported coverage, otherwise fail closed as unsupported region.
4. Enforce per-device e.g. **30 routes/day**, per-IP e.g. **2 requests/sec** with burst 5, global **1 request/sec** or low bounded concurrency for pilot only. These are **initial proposed conservative caps**, not proven capacity. Control edge and origin; prevent bypassing the edge.
5. Add request timeout (~5 s edge, bounded upstream), 429 with Retry-After, bounded JSON response and no unbounded queues/retries. Add hard Docker resources after testing realistic route coverage. Protect OmniRoute and other services' RAM/CPU.
6. Metrics should cover 2xx/4xx/429/5xx, cost profile, region, p50/p95/p99 latency, concurrent jobs, effective cache hits, CPU, RSS + page cache, swap, disk IO, bandwidth. Do not log precise coordinates, request bodies, customer addresses, or tokens.
7. Canary: invite a small group of couriers in several countries (prioritize Germany and Poland, alongside Lithuania where coverage already exists), measure real cross-border routing quality and latency, keep a kill switch, and roll back if needed. Do not treat the currently installed Lithuania tiles as a market strategy. Keep the private production gateway entirely separate.
8. For broad European coverage, a **separate routing VM** is safer than colocating an unbounded multi-country graph with OmniRoute. Start with measured high-demand regions, not a claim that a small free VM can hold all of Europe.

## Free/low-cost hosting realities

- **Oracle Cloud Always Free Ampere A1:** published allowance equivalent to **2 OCPU and 12 GB RAM** (subject to regional capacity/tenancy limits). The best candidate for an independent, *small-region* Valhalla VM if an account can provision it. A separate VM avoids production VPS contention. See https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm
- **Google Cloud Run:** monthly free requests/CPU/memory allowance can be useful for on-demand routing **with a small image and storage plan**, but bringing many OSM routing tiles on every cold start is expensive in latency/IO and may incur storage/network costs. Not a promise of zero-cost always-on full Europe. https://cloud.google.com/run/pricing
- **Render Free:** instances sleep after 15 min and can take about a minute to wake; filesystem is ephemeral and free instances cannot mount persistent disks. Bad for local-tile Valhalla live-offer latency. https://render.com/docs/free
- **Cloudflare Workers Free:** excellent for auth, quotas, filtering and forwarding, **not** for running Valhalla routing C++ and large OSM graphs inside Workers (128 MB memory, 10 ms CPU on Free). Also its 100k daily shared account requests matter if other projects use the account. https://developers.cloudflare.com/workers/platform/limits/

### Approval before public exposure

Public beta changes the privacy and abuse boundary. Leave existing private token/gateway unchanged until a region, capped quota, registration/consent flow and shutdown plan are verified. Opening `0.0.0.0:8002`, sharing a static bearer in the APK, or proxying unlimited requests under one public key are explicitly **not acceptable** deployments.
