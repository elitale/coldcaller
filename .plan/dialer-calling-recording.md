# Plan — Working dialer + recent calls + auto call recording

## Goal
1. Make an outbound call actually connect with two-way audio.
2. Persist every call and show it in the dialer's "Recent Calls".
3. Auto-record every call to local disk, organised date-wise.

## Root-cause gaps (found in code)
- `TelephonyService.sendInvite` sends INVITE with **no digest auth** → Twilio returns `407`,
  and `processResponse` just calls `onCallEnded` → call drops. (Same bug REGISTER had.)
- `handleInvite200Ok` calls `startAudio(null, 0)` — the remote SDP is never parsed, so RTP
  never has a destination → no audio.
- `hangUp()` sends no BYE and never fires `onCallEnded` → a user-initiated hangup is never
  persisted → Recent Calls stays empty.
- `MainWindow` sets the dialer's recent-calls list to an empty `ObservableList` that is never
  refreshed.
- No recording subsystem exists (`recording_path` column + `Call.recordingPath` are placeholders).

## Design

### Telephony — make calls connect
- `SipDigestAuth` (new): shared MD5 digest (RFC 2617, qop=auth). Pure `digestResponse(...)`
  for unit testing + `answer(Response, proxy, creds, method, uri, HeaderFactory)`.
  `SipRegistrar` delegates to it (single source of truth).
- `SdpParser` (new): parse `c=IN IP4 <ip>` + `m=audio <port>` → `MediaTarget(ip, port)`.
- `TelephonyService`:
  - Track `PendingInvite(from, to, callId, fromTag, requestUri, attempts)` per call.
  - INVITE responses: `200` → parse SDP → `startAudio(ip, port)` + `onCallAnswered`;
    `401/407` → rebuild INVITE with auth header (new CSeq, same Call-ID/From-tag) and resend;
    `>=300` → `onCallEnded`.
  - Store the answered `Dialog`; `hangUp()` sends BYE on it (best-effort) and fires
    `onCallEnded(callId, "hangup")`.

### Recording — date-wise WAV
- `RecordingPaths` (new): `~/.coldcalling/recordings/yyyy-MM-dd/HHmmss_<remote>.wav`.
- `WavFileWriter` (new): streaming 16-bit PCM mono WAV; patches RIFF sizes on close.
- `CallRecorder` (new): driven off the mic cadence (every 20 ms) — mixes the latest remote
  frame with the mic frame (clamped) and writes one mono frame. Remote frames buffered in a
  concurrent queue.
- `AudioPipeline`: optional recorder; `onMicFrame` (every read) + `onRemoteFrame` (on receive).
- `TelephonyService.startAudio` creates the recorder; `stopAudio` closes it and stores the
  path per call. `takeRecordingPath(callId)` lets `CallService` persist it.

### Recent calls
- `RecentCallFormatter` (new, ui.support): `Call` → one display row.
- `MainWindow.refreshRecentCalls()`: loads `callService.findRecent(N)`, formats, updates list
  on the FX thread.
- `ColdCallingApp`: refresh on startup and inside `onCallEnded`.

## TDD
Unit tests: `SipDigestAuthTest` (known RFC vector), `SdpParserTest`, `WavFileWriterTest`,
`RecordingPathsTest`, `CallRecorderTest`, `RecentCallFormatterTest`.

## Not doing (YAGNI)
- Re-INVITE/hold, multiple concurrent calls, RTCP stats, stereo recording, cloud backup,
  recording encryption, transcoding. PCMU/8000 mono only.
- Live Twilio + audio-device verification must be done by the user on the machine.
