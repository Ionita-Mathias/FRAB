# Phase 3.2 — Architecture & Diagrams

## 1. WebRTC signaling → direct P2P (sequence)

The Durable Object is an **ephemeral SDP/ICE broker only**. Once the DataChannel opens,
telemetry flows peer-to-peer and the DO goes idle (zero telemetry retention). The E2EE
key is generated on the emitter and shared to viewers only via the link fragment
(`#key`), which browsers never send to the server.

```mermaid
sequenceDiagram
    autonumber
    participant M as Mobile App (Emitter)
    participant W as CF Worker (API)
    participant DO as Session Durable Object
    participant V as Viewer Web App
    participant T as STUN/TURN (CF Realtime)

    Note over M,DO: 1 · Session bootstrap
    M->>W: POST /v1/sessions (app credential)
    W->>DO: create(sessionId = UUIDv4)
    W-->>M: { sessionId, emitterToken(256b), signalingUrl, iceServers }
    M->>M: generate AES-256 session key (never leaves device)
    M->>DO: WSS connect (emitterToken), role = publisher

    Note over V,DO: 2 · Viewer opens link  https://app/s/{id}#key
    V->>W: GET /v1/sessions/{id} (viewerToken)
    W-->>V: { signalingUrl, iceServers }
    V->>DO: WSS connect (viewerToken), role = subscriber
    DO-->>M: peer-joined { peerId }

    Note over M,V: 3 · Negotiation (SDP + ICE relayed by DO)
    M->>DO: offer(SDP) [peerId]
    DO-->>V: offer(SDP)
    V->>DO: answer(SDP)
    DO-->>M: answer(SDP)
    par ICE trickle
        M->>DO: ice candidate
        DO-->>V: ice candidate
    and
        V->>DO: ice candidate
        DO-->>M: ice candidate
    end
    M->>T: STUN binding / TURN allocate
    V->>T: STUN binding / TURN allocate

    Note over M,V: 4 · Direct P2P data path
    M->>V: DTLS handshake, DataChannel "telemetry" opens
    M-->>V: RouteMeta (AES-GCM) — once
    loop ~1 Hz while active
        M-->>V: TelemetryFrame (AES-GCM over SCTP/DTLS)
    end
    Note over DO: signaling idle · no telemetry stored

    alt Strict NAT (no candidate pair) OR viewers > N
        M->>DO: publish ciphertext frames
        DO-->>V: relay ciphertext (cannot decrypt)
    end

    Note over M,W: 5 · Teardown
    M->>W: DELETE /v1/sessions/{id}  (or SessionEnd)
    W->>DO: revoke → close all sockets, drop state
```

## 2. High-level component architecture

```mermaid
flowchart TB
    subgraph Athlete["Athlete side (trusted)"]
        W["Suunto watch<br/>display / nav · optional Broadcast-HR"]
        S["BLE strap / pod<br/>HR · RSC · CSC · Power"]
        M["Mobile Companion App (Emitter)<br/>GPS + BLE + WorkoutSession<br/>Protobuf encode · AES-GCM<br/>RTCPeerConnection mesh"]
    end
    W -. "BLE HR 0x180D" .-> M
    S -. "standard GATT" .-> M

    subgraph Edge["Cloudflare Edge — signaling only · zero retention"]
        WK["Worker API<br/>session/token · rate-limit · webhook sink"]
        DO["Session Durable Object<br/>SDP/ICE relay · presence<br/>optional ciphertext fan-out"]
    end
    ICE["STUN / TURN<br/>Cloudflare Realtime"]

    subgraph Viewers["Viewer side (trusted)"]
        V["Web App — Next.js PWA<br/>MapLibre GL + PMTiles<br/>dual-trace · metrics card<br/>route-snap / ETA · AES-GCM decrypt"]
    end

    M <-->|"WSS signaling (JSON SDP/ICE)"| DO
    V <-->|"WSS signaling"| DO
    M <-->|"ICE"| ICE
    V <-->|"ICE"| ICE
    M ==>|"WebRTC DataChannel — E2EE telemetry (PRIMARY)"| V
    M -. "fan-out fallback (ciphertext)" .-> DO
    DO -. "relay (ciphertext)" .-> V

    subgraph Suunto["Suunto Cloud — external · post-workout"]
        SC["Cloud / Partner API<br/>OAuth2 · WORKOUT_CREATED · FIT export"]
    end
    SC ==>|"HMAC webhook after sync"| WK
    WK ==>|"fetch FIT → reconcile authoritative track"| DO
```

## 3. C4 container diagram

```mermaid
C4Container
    title Container Diagram — LiveTrackSuunto

    Person(athlete, "Athlete", "Trains with a Suunto watch + phone")
    Person(viewer, "Viewer", "Follows the live session in a browser")

    System_Boundary(client, "Client tier — trusted, holds E2EE keys") {
        Container(mobile, "Mobile Companion App", "Swift / Kotlin (KMP)", "Captures GPS+BLE, encodes Protobuf, AES-GCM, publishes over WebRTC")
        Container(web, "Viewer Web App", "Next.js / TS PWA", "MapLibre dual-trace + live metrics; decrypts; route-snap/ETA")
    }

    System_Boundary(edge, "Signaling tier — untrusted relay, zero retention") {
        Container(worker, "Worker API", "Cloudflare Workers", "Session lifecycle, 256-bit tokens, rate-limit, webhook sink")
        ContainerDb(do, "Session Durable Object", "CF Durable Object", "SDP/ICE relay, presence, optional ciphertext fan-out")
        Container(turn, "STUN / TURN", "Cloudflare Realtime", "NAT traversal / DTLS packet relay")
    }

    System_Ext(suunto, "Suunto Cloud API", "OAuth2 · WORKOUT_CREATED webhook · FIT export (post-workout ground truth)")

    Rel(athlete, mobile, "Uses")
    Rel(viewer, web, "Uses")
    Rel(mobile, worker, "Create / revoke session", "HTTPS/JSON")
    Rel(web, worker, "Resolve session", "HTTPS/JSON")
    Rel(mobile, do, "Signaling", "WSS/JSON")
    Rel(web, do, "Signaling", "WSS/JSON")
    Rel(mobile, turn, "NAT traversal", "STUN/TURN")
    Rel(web, turn, "NAT traversal", "STUN/TURN")
    Rel(mobile, web, "E2EE telemetry (PRIMARY)", "WebRTC DataChannel / DTLS")
    Rel(mobile, do, "Ciphertext fan-out (fallback)", "WSS")
    Rel(do, web, "Ciphertext relay (fallback)", "WSS")
    Rel(suunto, worker, "Workout synced", "HMAC webhook")
```

## 4. Transport selection state machine

```mermaid
stateDiagram-v2
    [*] --> Negotiating
    Negotiating --> P2PDirect: candidate pair (host/srflx)
    Negotiating --> P2PTurn: only relay pair succeeds
    Negotiating --> EdgeFanout: ICE fails / viewers > N
    P2PDirect --> P2PTurn: network path change
    P2PDirect --> EdgeFanout: viewers cross threshold N
    P2PTurn --> EdgeFanout: relay unstable / viewers > N
    EdgeFanout --> P2PDirect: viewers drop & renegotiation succeeds
    P2PDirect --> [*]: SessionEnd
    P2PTurn --> [*]: SessionEnd
    EdgeFanout --> [*]: SessionEnd
```

**Threshold policy.** Emitter mesh is cheap for a handful of viewers; beyond
`N` (default **8**, configurable) the per-viewer `RTCPeerConnection` upload cost on a
phone becomes the bottleneck, so we switch that session to **edge fan-out**: the emitter
sends **one** ciphertext stream to the DO, which fans it to all subscribers. E2EE is
preserved because the DO only ever relays AES-GCM ciphertext it cannot decrypt.

## 5. Data-plane vs control-plane summary

| Plane | Path | Payload | Who can read it |
|---|---|---|---|
| Control (signaling) | Client ↔ DO (WSS) | JSON SDP/ICE, presence | Edge sees SDP/ICE only |
| Data — direct | Emitter ↔ Viewer (DTLS/SCTP) | AES-GCM(Protobuf) | Endpoints only |
| Data — TURN | Emitter ↔ TURN ↔ Viewer | DTLS + AES-GCM | Endpoints only (TURN relays opaque UDP) |
| Data — fan-out | Emitter → DO → Viewers (WSS) | AES-GCM(Protobuf) | Endpoints only (DO relays ciphertext) |
| Post-workout | Suunto → Worker → DO | HMAC webhook + FIT | Edge (transient, for reconciliation) |
