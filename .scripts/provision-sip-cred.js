#!/usr/bin/env node
/*
 * Provision a fresh Twilio SIP credential with a KNOWN password, then write it
 * to .scripts/creds.txt so sip-register-test.js can validate REGISTER.
 *
 * Twilio never returns an existing credential's password, so to test REGISTER
 * we must create a new credential we control. This:
 *   1. Finds (or creates) the target SIP domain.
 *   2. Finds a credential list mapped to that domain's registration auth
 *      (creates + maps "coldCalling" if none exists).
 *   3. Creates a new credential with a generated username/password.
 *   4. Writes SIP_USER / SIP_PASS / SIP_DOMAIN to .scripts/creds.txt.
 *
 * No npm dependencies — built-in `https` + `crypto` only.
 *
 * Required env: SIP_TWILIO_SID, SIP_TWILIO_TOKEN
 * Optional env: SIP_DOMAIN (default elitale.sip.twilio.com)
 *
 * The auth token is read from the environment only — never hard-coded.
 */

'use strict';

const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const SID = process.env.SIP_TWILIO_SID;
const TOKEN = process.env.SIP_TWILIO_TOKEN;
const DOMAIN = process.env.SIP_DOMAIN || 'elitale.sip.twilio.com';
const CRED_LIST_NAME = 'coldCalling';

if (!SID || !TOKEN) {
  console.error('Missing SIP_TWILIO_SID / SIP_TWILIO_TOKEN env vars.');
  process.exit(2);
}

function api(method, urlPath, form) {
  return new Promise((resolve, reject) => {
    const body = form ? new URLSearchParams(form).toString() : null;
    const req = https.request(
      {
        method,
        hostname: 'api.twilio.com',
        path: urlPath,
        auth: `${SID}:${TOKEN}`,
        headers: body
          ? {
              'Content-Type': 'application/x-www-form-urlencoded',
              'Content-Length': Buffer.byteLength(body),
            }
          : {},
      },
      (res) => {
        let data = '';
        res.on('data', (chunk) => (data += chunk));
        res.on('end', () => {
          let parsed;
          try {
            parsed = data ? JSON.parse(data) : {};
          } catch (_) {
            parsed = { raw: data };
          }
          if (res.statusCode >= 200 && res.statusCode < 300) {
            resolve(parsed);
          } else {
            reject(new Error(`${res.statusCode} ${method} ${urlPath}: ${parsed.message || data}`));
          }
        });
      },
    );
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

function randomHex(bytes) {
  return crypto.randomBytes(bytes).toString('hex');
}

/** Twilio requires >=12 chars, with at least one upper, lower, and digit. */
function generatePassword() {
  const upper = 'ABCDEFGHJKLMNPQRSTUVWXYZ';
  const lower = 'abcdefghijkmnpqrstuvwxyz';
  const digit = '23456789';
  const all = upper + lower + digit;
  const pick = (set) => set[crypto.randomInt(set.length)];
  let pw = pick(upper) + pick(lower) + pick(digit);
  for (let i = pw.length; i < 16; i += 1) pw += pick(all);
  return pw;
}

async function findDomainSid() {
  const res = await api('GET', `/2010-04-01/Accounts/${SID}/SIP/Domains.json?PageSize=100`);
  const match = (res.domains || []).find((d) => d.domain_name === DOMAIN);
  return match ? match.sid : null;
}

async function ensureMappedCredentialList(domainSid) {
  // Prefer a credential list already mapped to this domain's registration auth.
  const mappings = await api(
    'GET',
    `/2010-04-01/Accounts/${SID}/SIP/Domains/${domainSid}/Auth/Registrations/CredentialListMappings.json?PageSize=100`,
  );
  const existing = mappings.contents || mappings.credential_list_mappings || [];
  if (existing.length > 0) {
    return existing[0].sid;
  }

  // None mapped — find or create the named list, then map it.
  const lists = await api('GET', `/2010-04-01/Accounts/${SID}/SIP/CredentialLists.json?PageSize=100`);
  let list = (lists.credential_lists || []).find((l) => l.friendly_name === CRED_LIST_NAME);
  if (!list) {
    list = await api('POST', `/2010-04-01/Accounts/${SID}/SIP/CredentialLists.json`, {
      FriendlyName: CRED_LIST_NAME,
    });
  }
  await api(
    'POST',
    `/2010-04-01/Accounts/${SID}/SIP/Domains/${domainSid}/Auth/Registrations/CredentialListMappings.json`,
    { CredentialListSid: list.sid },
  );
  return list.sid;
}

async function main() {
  let domainSid = await findDomainSid();
  if (!domainSid) {
    console.error(`Domain ${DOMAIN} not found on this account.`);
    process.exit(1);
  }

  const credListSid = await ensureMappedCredentialList(domainSid);

  const username = `cctest${randomHex(3)}`;
  const password = generatePassword();
  await api(
    'POST',
    `/2010-04-01/Accounts/${SID}/SIP/CredentialLists/${credListSid}/Credentials.json`,
    { Username: username, Password: password },
  );

  const credsPath = path.join(__dirname, 'creds.txt');
  fs.writeFileSync(
    credsPath,
    `SIP_USER=${username}\nSIP_PASS=${password}\nSIP_DOMAIN=${DOMAIN}\n`,
    { mode: 0o600 },
  );

  console.log(`Provisioned credential ${username} on ${DOMAIN}`);
  console.log(`Credential list: ${credListSid}`);
  console.log(`Wrote ${credsPath} (password hidden).`);
}

main().catch((err) => {
  console.error(`Provisioning failed: ${err.message}`);
  process.exit(1);
});
