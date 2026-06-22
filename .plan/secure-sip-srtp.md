# Secure Calling — TLS Signaling + SRTP Media (Twilio Secure Media)

## Why
Twilio SIP Domain `elitale.sip.twilio.com` has **Secure Media** enabled. It rejects
plain UDP INVITEs with `488 Secure SIP transport required` / `X-Twilio-Error: 32209`.
A secure call requires **TLS for SIP signaling** AND **SRTP for media** (RTP/SAVP + SDES crypto).

## Locked decisions
- Keep media codec G.711 PCMU (payload type 0). Only wrap it in SRTP.
- SRTP suite: implement `AES_CM_128_HMAC_SHA1_80` (SDES). Confirm Twilio negotiates this
  before finalizing (Phase 1 capture). If Twilio insists on GCM, revisit.
- No new dependencies. SRTP primitives via JCA (`AES/CTR/NoPadding`, `HmacSHA1`).
  Key derivation + IV + auth tag per RFC 3711 §4.3 / §4.1, hand-rolled and unit-tested
  with RFC 3711 published test vectors.
- TLS uses the JVM default `SSLSocketFactory` (trusts cacerts → Twilio's public CA cert).
  No client cert (Twilio authenticates via SIP digest, not mTLS). No bundled keystore.
- Do NOT change the user's stored proxy port (5060). For TLS, target remote port 5061
  implicitly via `;transport=tls` on the Request-URI (NIST default tls port = 5061).

## Phase 1 — TLS signaling  (deliverable, independently testable)
- `SipEngine`: configurable transport (default `tls`); `createListeningPoint(ip, port, "tls")`;
  stack props `TLS_CLIENT_AUTH_TYPE=Disabled`, `TLS_CLIENT_PROTOCOLS=TLSv1.2,TLSv1.3`;
  expose `transport()`.
- `SipRegistrar` Via transport → `engine.transport()`; Contact URI `;transport=tls`;
  REGISTER Request-URI `;transport=tls`.
- `TelephonyService.buildInvite` Via transport → `engine.transport()`; Contact URI `;transport=tls`;
  INVITE Request-URI `;transport=tls`.
- Build + user dials → expect to pass `488` signaling gate, reach digest `407` → media negotiation.
- **CAPTURE Twilio's negotiated SDP** (200 OK or the media-level 488) to confirm SRTP suite/format.

## Phase 2 — SRTP media  (after Phase 1 capture)
- `SdpBuilder`: `m=audio <port> RTP/SAVP 0` + `a=crypto:1 AES_CM_128_HMAC_SHA1_80 inline:<b64(key||salt)>`.
  Generate fresh 16B master key + 14B master salt per call.
- `SdpParser`: parse remote `a=crypto:` (tag, suite, inline key) from the answer.
- `SrtpContext` (new, telephony.rtp.srtp): RFC 3711 AES_CM_128_HMAC_SHA1_80.
  - Key derivation (labels 0x00/0x01/0x02) → k_e(16), k_a(20), k_s(14).
  - `protect(rtpPacket)` → AES-CTR encrypt payload + append 10B HMAC-SHA1-80 tag; ROC mgmt.
  - `unprotect(srtpPacket)` → verify tag, AES-CTR decrypt; ROC mgmt.
  - Tested with RFC 3711 §B.2/§B.3 vectors.
- `SecureRtpSession` (new): raw `DatagramSocket` RTP (jlibrtp cannot do SRTP).
  Build/parse 12B RTP header (PT=0 PCMU, seq, ts+=160, random SSRC); two SRTP contexts
  (send = our key/SSRC; receive = Twilio key, SSRC learned from first packet).
- Wire `startAudio`/`answer`/`buildInvite`/`buildAnswer` to pass crypto material + use SecureRtpSession.

## Status
- [ ] Phase 1: TLS signaling
- [ ] Phase 1: capture Twilio negotiated SDP
- [ ] Phase 2: SDP crypto offer/answer
- [ ] Phase 2: SrtpContext + RFC vector tests
- [ ] Phase 2: SecureRtpSession + wiring
- [ ] Full build + live call verification
