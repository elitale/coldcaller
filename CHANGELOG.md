# Changelog

All notable changes to **coldCalling** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-06-23

First public release of **coldCalling** — a cross-platform desktop dialer for
outbound and inbound cold calling. Pure SIP + RTP (no browser, no WebRTC), built
on Java 21 + JavaFX 21, and shipped as a native installer for macOS, Windows, and
Linux.

### Added

#### Calling
- Outbound and inbound SIP calls through Twilio (REST API + SIP registration).
- Screen-first active-call experience: Mute, Keypad (DTMF), Hold, and Hang-up.
- Real-time microphone level meter and audio visualization during a call.
- One-tap call dispositions (Interested, Callback, Not Interested, DNC, …).
- Call notes that auto-save continuously as you type, with prior-call context.
- Redial from the wrap-up and call-failed screens.
- Human-readable failure reasons mapped from every SIP error response.
- Voicemail drop — play a pre-recorded greeting into a call with one key (`V`).
- Mini Call HUD — an always-on-top, draggable call pill so a live call never
  disappears when you switch to your CRM or spreadsheet.
- Local call recording saved as WAV files.

#### Power Dialer
- Load a call list and auto-dial through it hands-free (Start / Pause / Stop).
- Auto-advance on disposition, busy, no-answer, and failure.
- Live Dialed / Connected / Remaining counters and a "next up" queue preview.
- Configurable no-answer and auto-advance timeouts.

#### Contacts, History & SMS
- Contact management with company, tags, notes, and per-contact call history.
- Non-blocking number-detail side panel with quick actions.
- Full call history with duration, disposition, and direction filters.
- Two-way SMS threads per number — outbound via Twilio REST, inbound polled in
  real time.
- DNC (Do-Not-Call) enforcement checked before every outbound dial.

#### Telephony & Audio
- G.711 PCMU audio pipeline (8 kHz, mono) over RTP (jlibrtp).
- SIP registration with 60-second refresh and TLS keep-alive.
- Custom STUN client for NAT traversal.
- Audio-device enumeration with live input/output testing and mid-call device
  switching.
- Resilient audio: the call stays alive if the configured mixer goes stale.
- Per-account call routing — provisions a SIP-to-PSTN bridge into the user's own
  Twilio account so outbound calls reach the PSTN.

#### Onboarding & Settings
- Guided onboarding with secure telephony credential setup and Twilio SIP
  provisioning.
- Searchable country / caller-ID picker with flags and full keyboard dialing.
- Light, Dark, and System-auto themes (Apple HIG design system).
- Reduce Motion setting for meaningful, fatigue-free motion across the app.

#### Platform & Packaging
- Native installers via jpackage — macOS DMG, Windows MSI, Linux DEB/RPM.
- GitHub Actions build-and-test CI workflow.
- SQLite storage with FlywayDB migrations at `~/.coldcalling/data.db`.

### Security
- SIP credentials and API keys stored in the OS keychain, never in plaintext.
- DNC enforced at the service layer, not only in the UI.
- Call recordings stay local unless cloud backup is explicitly enabled.

[1.0.0]: https://github.com/elitale/coldcaller/releases/tag/v1.0.0
