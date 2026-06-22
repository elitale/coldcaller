#!/usr/bin/env node
/*
 * Standalone SIP REGISTER tester for Twilio SIP Domains.
 *
 * Validates a SIP username/password against a Twilio SIP domain by performing a
 * real REGISTER over UDP with MD5 Digest authentication — independent of the
 * Java JAIN-SIP stack. Use it to confirm whether provisioned credentials work
 * when the desktop app's SIP test fails to start.
 *
 * No npm dependencies — uses only Node's built-in `dgram` and `crypto`.
 *
 * Usage:
 *   node .scripts/sip-register-test.js \
 *     --user coldcalling1cfc \
 *     --pass 'THE_GENERATED_PASSWORD' \
 *     --domain elitale.sip.twilio.com \
 *     [--proxy elitale.sip.twilio.com] [--port 5060] [--expires 60]
 *
 * Or via env vars: SIP_USER, SIP_PASS, SIP_DOMAIN, SIP_PROXY, SIP_PORT, SIP_EXPIRES
 *
 * Never commit real credentials. Pass them at runtime only.
 */

'use strict';

const dgram = require('dgram');
const crypto = require('crypto');

function parseArgs(argv) {
  const args = {};
  for (let i = 2; i < argv.length; i += 1) {
    const token = argv[i];
    if (token.startsWith('--')) {
      const key = token.slice(2);
      const value = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[(i += 1)] : 'true';
      args[key] = value;
    }
  }
  return args;
}

function md5(input) {
  return crypto.createHash('md5').update(input).digest('hex');
}

function randomHex(bytes) {
  return crypto.randomBytes(bytes).toString('hex');
}

/** Parse a WWW-Authenticate / Proxy-Authenticate Digest header into a key/value map. */
function parseChallenge(headerValue) {
  const out = {};
  const body = headerValue.replace(/^Digest\s+/i, '');
  const re = /(\w+)=("([^"]*)"|([^,]*))/g;
  let match;
  while ((match = re.exec(body)) !== null) {
    out[match[1].toLowerCase()] = match[3] !== undefined ? match[3] : match[4];
  }
  return out;
}

function buildDigestResponse(opts) {
  const { username, password, realm, nonce, method, uri, qop, nc, cnonce, opaque, algorithm } = opts;
  const ha1 = md5(`${username}:${realm}:${password}`);
  const ha2 = md5(`${method}:${uri}`);
  const response = qop
    ? md5(`${ha1}:${nonce}:${nc}:${cnonce}:${qop}:${ha2}`)
    : md5(`${ha1}:${nonce}:${ha2}`);

  let header =
    `Digest username="${username}", realm="${realm}", nonce="${nonce}", ` +
    `uri="${uri}", response="${response}"`;
  if (algorithm) header += `, algorithm=${algorithm}`;
  if (qop) header += `, qop=${qop}, nc=${nc}, cnonce="${cnonce}"`;
  if (opaque) header += `, opaque="${opaque}"`;
  return header;
}

function buildRegister(ctx, authHeader) {
  const { domain, localIp, localPort, callId, fromTag, branch, cseq, user, expires } = ctx;
  const aor = `sip:${user}@${domain}`;
  const lines = [
    `REGISTER sip:${domain} SIP/2.0`,
    `Via: SIP/2.0/UDP ${localIp}:${localPort};branch=${branch};rport`,
    `Max-Forwards: 70`,
    `From: <${aor}>;tag=${fromTag}`,
    `To: <${aor}>`,
    `Call-ID: ${callId}`,
    `CSeq: ${cseq} REGISTER`,
    `Contact: <sip:${user}@${localIp}:${localPort}>`,
    `Expires: ${expires}`,
    `User-Agent: coldCalling-sip-test/1.0`,
  ];
  if (authHeader) lines.push(authHeader);
  lines.push('Content-Length: 0');
  return lines.join('\r\n') + '\r\n\r\n';
}

function parseStatus(message) {
  const firstLine = message.split('\r\n', 1)[0];
  const m = firstLine.match(/^SIP\/2\.0\s+(\d{3})\s+(.*)$/);
  return m ? { code: parseInt(m[1], 10), reason: m[2].trim() } : null;
}

function getHeader(message, name) {
  const re = new RegExp(`^${name}:\\s*(.*)$`, 'im');
  const m = message.match(re);
  return m ? m[1].trim() : null;
}

async function main() {
  const args = parseArgs(process.argv);
  const user = args.user || process.env.SIP_USER;
  const pass = args.pass || process.env.SIP_PASS;
  const domain = args.domain || process.env.SIP_DOMAIN;
  const proxy = args.proxy || process.env.SIP_PROXY || domain;
  const port = parseInt(args.port || process.env.SIP_PORT || '5060', 10);
  const expires = parseInt(args.expires || process.env.SIP_EXPIRES || '60', 10);

  if (!user || !pass || !domain) {
    console.error('Missing required input.\n');
    console.error('Usage: node .scripts/sip-register-test.js --user <u> --pass <p> --domain <d.sip.twilio.com> [--proxy <host>] [--port 5060]');
    console.error('Or set SIP_USER, SIP_PASS, SIP_DOMAIN env vars.');
    process.exit(2);
  }

  const socket = dgram.createSocket('udp4');
  const TIMEOUT_MS = 8000;

  const finish = (code, summary) => {
    try { socket.close(); } catch (_) { /* ignore */ }
    console.log(`\n${summary}`);
    process.exit(code);
  };

  await new Promise((resolve) => socket.bind(0, resolve));
  const localPort = socket.address().port;
  // Local IP shown in headers; for NAT'd UDP the proxy uses rport so this is informational.
  const localIp = '0.0.0.0';

  const ctx = {
    domain,
    user,
    localIp,
    localPort,
    callId: `${randomHex(12)}@coldcalling-test`,
    fromTag: randomHex(6),
    branch: `z9hG4bK${randomHex(8)}`,
    cseq: 1,
    expires,
  };

  let attemptedAuth = false;
  let lastAuthHeader = null;

  const timer = setTimeout(
    () => finish(1, 'FAIL: timed out waiting for a SIP response (check network/UDP 5060 reachability).'),
    TIMEOUT_MS,
  );

  socket.on('error', (err) => {
    clearTimeout(timer);
    finish(1, `FAIL: socket error: ${err.message}`);
  });

  socket.on('message', (buf) => {
    const message = buf.toString('utf8');
    const status = parseStatus(message);
    if (!status) return;
    console.log(`<-- ${status.code} ${status.reason}`);

    if (status.code === 200) {
      clearTimeout(timer);
      finish(0, `SUCCESS: credentials accepted by ${domain} (200 OK).`);
      return;
    }

    if (status.code === 423) {
      // Interval too brief — bump Expires to the server minimum and retry.
      const minExpires = parseInt(getHeader(message, 'Min-Expires') || '3600', 10);
      ctx.expires = Number.isNaN(minExpires) ? 3600 : minExpires;
      ctx.cseq += 1;
      ctx.branch = `z9hG4bK${randomHex(8)}`;
      const reg = buildRegister(ctx, lastAuthHeader);
      console.log(`--> REGISTER (Expires=${ctx.expires})`);
      socket.send(reg, port, proxy);
      return;
    }

    if ((status.code === 401 || status.code === 407) && !attemptedAuth) {
      attemptedAuth = true;
      const challengeHeader =
        getHeader(message, 'WWW-Authenticate') || getHeader(message, 'Proxy-Authenticate');
      if (!challengeHeader) {
        clearTimeout(timer);
        finish(1, `FAIL: ${status.code} received but no auth challenge header present.`);
        return;
      }
      const c = parseChallenge(challengeHeader);
      const isProxy = status.code === 407;
      ctx.cseq += 1;
      ctx.branch = `z9hG4bK${randomHex(8)}`;
      const digest = buildDigestResponse({
        username: user,
        password: pass,
        realm: c.realm,
        nonce: c.nonce,
        method: 'REGISTER',
        uri: `sip:${domain}`,
        qop: c.qop ? 'auth' : null,
        nc: '00000001',
        cnonce: randomHex(8),
        opaque: c.opaque,
        algorithm: c.algorithm,
      });
      const authHeaderName = isProxy ? 'Proxy-Authorization' : 'Authorization';
      lastAuthHeader = `${authHeaderName}: ${digest}`;
      const reg = buildRegister(ctx, lastAuthHeader);
      console.log(`--> REGISTER (with ${authHeaderName})`);
      socket.send(reg, port, proxy);
      return;
    }

    clearTimeout(timer);
    finish(1, `FAIL: ${status.code} ${status.reason}.`);
  });

  const initial = buildRegister(ctx, null);
  console.log(`Testing SIP REGISTER as ${user} -> ${proxy}:${port} (domain ${domain})`);
  console.log('--> REGISTER (no auth)');
  socket.send(initial, port, proxy);
}

main().catch((err) => {
  console.error(`Unexpected error: ${err.stack || err.message}`);
  process.exit(1);
});
