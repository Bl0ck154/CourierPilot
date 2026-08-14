# Valhalla Self-Hosting Plan

Status: deployment handoff / future CourierPilot routing backend

This document is intended for an AI/server agent with shell access to the user's VPS.

## Goal

Deploy a private Valhalla routing service for CourierPilot using OpenStreetMap data for Lithuania, with Vilnius as the primary use case.

The first deployment is a routing research backend, not yet a production dependency of the Android app.

## Source of truth

Use current official Valhalla documentation and official Valhalla container images. Do not copy old third-party Docker recipes unless the official image cannot satisfy a requirement.

Official image currently recommended for the scripted workflow:

`ghcr.io/valhalla/valhalla-scripted:latest`

Official Lithuania OSM extract:

`https://download.geofabrik.de/europe/lithuania-latest.osm.pbf`

References:

- https://github.com/valhalla/valhalla
- https://github.com/valhalla/valhalla/blob/master/docker/README.md
- https://valhalla.github.io/valhalla/
- https://download.geofabrik.de/europe/lithuania.html

## Deployment requirements

1. Inspect the VPS first: OS, CPU, RAM, free disk, Docker/Compose version, existing reverse proxies, firewall, ports and running services.
2. Do not break or replace unrelated services.
3. Use persistent storage under `/opt/valhalla` (or another clearly documented durable path if the server layout requires it).
4. Download/build only Lithuania initially.
5. Persist PBF, config, admin/timezone DBs and routing tiles across container restarts.
6. Use the official scripted image so PBF changes can trigger a controlled rebuild.
7. Do not expose raw port 8002 openly to the Internet without a reason.
8. Prefer binding Valhalla to localhost and putting HTTPS/authentication in the existing reverse proxy when a suitable domain/subdomain is available.
9. If no safe public endpoint can be created without additional user infrastructure, leave it localhost-only and report exactly what remains.
10. Add health checks and a reproducible route smoke test in Vilnius.

## Suggested Docker Compose baseline

Create `/opt/valhalla/docker-compose.yml` similar to:

```yaml
services:
  valhalla:
    image: ghcr.io/valhalla/valhalla-scripted:latest
    container_name: courierpilot-valhalla
    restart: unless-stopped
    ports:
      - "127.0.0.1:8002:8002"
    volumes:
      - ./custom_files:/custom_files
    environment:
      tile_urls: https://download.geofabrik.de/europe/lithuania-latest.osm.pbf
      build_admins: "True"
      build_time_zones: "True"
      build_transit: "False"
      build_elevation: "False"
      build_tar: "True"
      serve_tiles: "True"
      use_tiles_ignore_pbf: "True"
      update_existing_config: "True"
      server_threads: "2"
```

Do not blindly keep `server_threads=2`; choose a conservative value based on the real VPS resources. The official image documentation notes that lowering thread count can help if tile building is killed under memory pressure.

## Initial build

- Create `/opt/valhalla/custom_files`.
- Start the container.
- Follow logs until the Lithuania graph is built and the HTTP service is actually listening.
- Verify generated persistent artifacts exist in `custom_files`.
- Verify container restart does not unnecessarily destroy/rebuild a healthy graph.

## API smoke tests

Use Vilnius coordinate pairs and test at least:

### Pedestrian candidate

```json
{
  "locations": [
    {"lat": 54.6872, "lon": 25.2797},
    {"lat": 54.7005, "lon": 25.3030}
  ],
  "costing": "pedestrian",
  "costing_options": {
    "pedestrian": {
      "step_penalty": 3600
    }
  },
  "directions_options": {
    "units": "kilometers"
  }
}
```

If the installed Valhalla version rejects that exact penalty value, inspect the current API constraints and use the highest sensible accepted value. The intent is strong stair avoidance, not reliance on one magic number.

### Bicycle/cycleway candidate

```json
{
  "locations": [
    {"lat": 54.6872, "lon": 25.2797},
    {"lat": 54.7005, "lon": 25.3030}
  ],
  "costing": "bicycle",
  "costing_options": {
    "bicycle": {
      "bicycle_type": "hybrid",
      "use_roads": 0.2,
      "use_hills": 0.5,
      "avoid_bad_surfaces": 0.2,
      "cycling_speed": 25
    }
  },
  "directions_options": {
    "units": "kilometers"
  }
}
```

Treat these values as starting points for experiments, not final courier-routing truth.

For each response verify:

- HTTP success;
- trip summary exists;
- distance is plausible;
- duration is plausible as a baseline only;
- encoded route shape exists;
- no unexpected routing outside Lithuania/Vilnius.

## Courier routing research profile

Desired real behavior is approximately:

- use short pedestrian-like urban shortcuts where practical;
- strongly avoid steps/stairs;
- prefer cycleways when they do not create a pointless detour;
- ordinary roads/paths are acceptable;
- route geometry/distance matters more initially than generic ETA.

Stock Valhalla does not provide one exact costing profile for this combination.

Therefore do not create a fake `courier` API mode by merely renaming `pedestrian`.

First expose/test both stock candidates and collect real Vilnius route comparisons. A custom Sif costing implementation/fork is a later option if the data proves necessary.

## Security for later Android use

When CourierPilot starts calling the VPS from a phone:

- use HTTPS;
- add authentication/rate limiting in a reverse proxy because Valhalla itself is not the application's user-auth boundary;
- do not place secrets in the GitHub repository;
- keep the backend endpoint configurable;
- remember that route coordinates are location data and are sensitive;
- Android routing integration will require network permission, unlike the current local-only capture architecture.

## Map updates

Do not add an aggressive updater on day one.

After the deployment is stable, implement a simple documented weekly update job that:

1. downloads the latest Lithuania PBF to a temporary file;
2. replaces the current PBF only after a successful download;
3. restarts/rebuilds Valhalla in a controlled way;
4. retains logs;
5. validates a known Vilnius route after rebuild;
6. rolls back or leaves the previous healthy data in place on failure where practical.

## Deliverables from the server agent

Return:

- exact VPS resource summary;
- installed Docker/Compose versions;
- Valhalla image/tag actually deployed;
- directory layout;
- Docker Compose file;
- disk usage after Lithuania build;
- startup/build duration observed (report actual result, do not estimate in advance);
- health/status output;
- pedestrian Vilnius smoke-test distance/time;
- bicycle Vilnius smoke-test distance/time;
- whether the service is localhost-only or has a protected HTTPS endpoint;
- endpoint/base URL if safe to expose to CourierPilot;
- any errors/warnings encountered and how they were resolved;
- commands to update/restart/inspect logs.

Do not modify the CourierPilot Android repository as part of the server deployment unless explicitly asked after the routing server is validated.
