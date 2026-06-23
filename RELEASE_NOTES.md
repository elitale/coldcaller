# coldCalling v1.0.0

**Released 2026-06-23**

coldCalling is a cross-platform desktop dialer for outbound and inbound cold
calling — part of the [coldBirds](https://coldbirds.com) outreach suite. It runs on
pure SIP + RTP (no browser, no WebRTC), is built on Java 21 + JavaFX 21, and ships
as a native installer for macOS, Windows, and Linux.

This is the first stable release.

## Highlights

- **A real dialer, not a web phone.** Native SIP calling through Twilio with a
  G.711 audio pipeline, mid-call device switching, and a live mic meter.
- **Power dialer.** Load a list and auto-advance through it hands-free, with live
  Dialed / Connected / Remaining counters and a queue preview.
- **Voicemail drop.** Leave a pre-recorded greeting in one keystroke (`V`) — the
  power dialer advances for you.
- **Mini Call HUD.** A draggable, always-on-top call pill so your live call never
  disappears behind the CRM or spreadsheet.
- **Two-way SMS.** Per-number inbound and outbound message threads.
- **Built for long shifts.** Apple-HIG UI with Light / Dark / System themes and a
  Reduce Motion option for meaningful, fatigue-free motion.

## What's included

**Calling** — outbound and inbound SIP calls; Mute / Keypad / Hold / Hang-up;
real-time mic meter; one-tap dispositions; continuously auto-saving notes with
prior-call context; redial; human-readable SIP failure reasons; voicemail drop;
Mini Call HUD; local WAV recording.

**Power dialer** — hands-free auto-dial with Start / Pause / Stop, auto-advance on
disposition/busy/no-answer/failure, live counters, and configurable timeouts.

**Leads, history & SMS** — lead management with tags and notes, a
non-blocking number-detail panel, filterable call history, two-way SMS, and
service-level DNC enforcement before every dial.

**Telephony & audio** — G.711 PCMU over RTP, SIP registration with TLS keep-alive,
a custom STUN client for NAT traversal, live audio-device testing, mid-call device
switching, and per-account SIP-to-PSTN call routing into your own Twilio account.

**Onboarding & settings** — guided setup with secure credential storage and Twilio
SIP provisioning, a searchable country / caller-ID picker, and theme controls.

## Install

Download the installer for your platform from the release assets:

- **macOS** — `coldCalling-1.0.0.dmg`
- **Windows** — `coldCalling-1.0.0.msi`
- **Linux** — `coldCalling-1.0.0.deb` / `.rpm`

The Java runtime is bundled — no separate JDK install is required. A Twilio
account (Account SID, Auth Token, and a SIP-capable phone number) is needed to
place calls; the in-app onboarding walks you through setup.

## Known limitations

- Automatic voicemail / answering-machine detection (AMD) is not included —
  voicemail drop is manual (one keystroke).
- Per-number reputation and "Scam Likely" health monitoring are planned for a
  future release.
- Call waiting / simultaneous second inbound call handling is limited.

## Requirements

- A Twilio account with a SIP-capable phone number.
- macOS 12+, Windows 10+, or a modern Linux desktop.
- Inbound SMS uses the AWS relay; outbound SMS and all calling work without it.

---

Full details in the [CHANGELOG](CHANGELOG.md).
