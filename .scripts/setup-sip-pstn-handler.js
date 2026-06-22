#!/usr/bin/env node
/*
 * Configure the Programmable Voice SIP Domain so outbound calls from registered
 * SIP endpoints (the coldCalling desktop app) bridge to the PSTN.
 *
 * Why this is needed:
 *   A Programmable Voice SIP Domain hands every call from a registered client to
 *   its Voice webhook ("A call comes in"). With no webhook configured, Twilio has
 *   nothing to route the call to and answers the INVITE with "404 Not found" —
 *   exactly the symptom we saw. This script deploys a tiny Twilio Serverless
 *   Function that <Dial>s the dialed PSTN number, then points the SIP Domain's
 *   VoiceUrl at that function.
 *
 * Fully API-driven. No npm dependencies — built-in https + crypto + fs only,
 * matching provision-sip-cred.js.
 *
 * Required env: SIP_TWILIO_SID, SIP_TWILIO_TOKEN
 * Optional env: SIP_DOMAIN (default elitale.sip.twilio.com)
 *
 * Idempotent: re-running reuses the same Service / Function / Environment
 * (matched by unique name); it ships a fresh version and re-points the domain.
 */

'use strict';

const https = require('https');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');

const SID = process.env.SIP_TWILIO_SID;
const TOKEN = process.env.SIP_TWILIO_TOKEN;
const DOMAIN = process.env.SIP_DOMAIN || 'elitale.sip.twilio.com';

const SERVICE_UNIQUE_NAME = 'coldcalling-sip';
const FUNCTION_NAME = 'pstn-bridge';
const FUNCTION_PATH = '/pstn-bridge';
const ENV_UNIQUE_NAME = 'production';
const ENV_DOMAIN_SUFFIX = 'prod';
const VISIBILITY = 'protected';

const API = 'api.twilio.com';
const SLS = 'serverless.twilio.com';
const SLS_UPLOAD = 'serverless-upload.twilio.com';

if (!SID || !TOKEN) {
  console.error('Missing SIP_TWILIO_SID / SIP_TWILIO_TOKEN env vars.');
  process.exit(2);
}

const AUTH = `${SID}:${TOKEN}`;

function request(hostname, method, reqPath, { headers = {}, body = null } = {}) {
  return new Promise((resolve, reject) => {
    const req = https.request(
      { method, hostname, path: reqPath, auth: AUTH, headers },
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
            reject(new Error(`${res.statusCode} ${method} ${hostname}${reqPath}: ${parsed.message || data}`));
          }
        });
      },
    );
    req.on('error', reject);
    if (body) req.write(body);
    req.end();
  });
}

function form(host, method, reqPath, fields) {
  const body = new URLSearchParams(fields).toString();
  return request(host, method, reqPath, {
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      'Content-Length': Buffer.byteLength(body),
    },
    body,
  });
}

async function findDomainSid() {
  const res = await request(API, 'GET', `/2010-04-01/Accounts/${SID}/SIP/Domains.json?PageSize=100`);
  const match = (res.domains || []).find((d) => d.domain_name === DOMAIN);
  if (!match) {
    throw new Error(`SIP domain ${DOMAIN} not found on this account.`);
  }
  return match.sid;
}

async function ensureService() {
  const res = await request(SLS, 'GET', `/v1/Services?PageSize=100`);
  const found = (res.services || []).find((s) => s.unique_name === SERVICE_UNIQUE_NAME);
  if (found) {
    return found.sid;
  }
  const created = await form(SLS, 'POST', `/v1/Services`, {
    UniqueName: SERVICE_UNIQUE_NAME,
    FriendlyName: 'coldCalling SIP',
    IncludeCredentials: 'true',
    UiEditable: 'true',
  });
  return created.sid;
}

async function ensureFunction(serviceSid) {
  const res = await request(SLS, 'GET', `/v1/Services/${serviceSid}/Functions?PageSize=100`);
  const found = (res.functions || []).find((f) => f.friendly_name === FUNCTION_NAME);
  if (found) {
    return found.sid;
  }
  const created = await form(SLS, 'POST', `/v1/Services/${serviceSid}/Functions`, {
    FriendlyName: FUNCTION_NAME,
  });
  return created.sid;
}

async function uploadVersion(serviceSid, functionSid, content) {
  const boundary = `----coldcalling${crypto.randomBytes(12).toString('hex')}`;
  const field = (name, value) =>
    Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`);
  const fileHeader = Buffer.from(
    `--${boundary}\r\nContent-Disposition: form-data; name="Content"; filename="${FUNCTION_NAME}.js"\r\n` +
      `Content-Type: application/javascript\r\n\r\n`,
  );
  const body = Buffer.concat([
    field('Path', FUNCTION_PATH),
    field('Visibility', VISIBILITY),
    fileHeader,
    Buffer.from(content, 'utf8'),
    Buffer.from(`\r\n--${boundary}--\r\n`),
  ]);
  const res = await request(
    SLS_UPLOAD,
    'POST',
    `/v1/Services/${serviceSid}/Functions/${functionSid}/Versions`,
    {
      headers: {
        'Content-Type': `multipart/form-data; boundary=${boundary}`,
        'Content-Length': body.length,
      },
      body,
    },
  );
  return res.sid;
}

async function createBuild(serviceSid, versionSid) {
  const res = await form(SLS, 'POST', `/v1/Services/${serviceSid}/Builds`, {
    FunctionVersions: versionSid,
  });
  return res.sid;
}

async function waitForBuild(serviceSid, buildSid) {
  for (let attempt = 0; attempt < 90; attempt += 1) {
    const res = await request(SLS, 'GET', `/v1/Services/${serviceSid}/Builds/${buildSid}`);
    if (res.status === 'completed') {
      return;
    }
    if (res.status === 'failed') {
      throw new Error('Serverless build failed.');
    }
    await new Promise((resolve) => setTimeout(resolve, 2000));
  }
  throw new Error('Serverless build timed out.');
}

async function ensureEnvironment(serviceSid) {
  const res = await request(SLS, 'GET', `/v1/Services/${serviceSid}/Environments?PageSize=100`);
  const found = (res.environments || []).find((e) => e.unique_name === ENV_UNIQUE_NAME);
  if (found) {
    return found;
  }
  return form(SLS, 'POST', `/v1/Services/${serviceSid}/Environments`, {
    UniqueName: ENV_UNIQUE_NAME,
    DomainSuffix: ENV_DOMAIN_SUFFIX,
  });
}

async function deployBuild(serviceSid, environmentSid, buildSid) {
  await form(SLS, 'POST', `/v1/Services/${serviceSid}/Environments/${environmentSid}/Deployments`, {
    BuildSid: buildSid,
  });
}

async function pointDomainAtFunction(domainSid, url) {
  await form(API, 'POST', `/2010-04-01/Accounts/${SID}/SIP/Domains/${domainSid}.json`, {
    VoiceUrl: url,
    VoiceMethod: 'POST',
  });
}

async function main() {
  const source = fs.readFileSync(path.join(__dirname, 'sip-pstn-handler', 'pstn-bridge.js'), 'utf8');

  console.log(`Resolving SIP domain ${DOMAIN} …`);
  const domainSid = await findDomainSid();

  console.log('Ensuring Serverless service …');
  const serviceSid = await ensureService();

  console.log('Ensuring function …');
  const functionSid = await ensureFunction(serviceSid);

  console.log('Uploading function version …');
  const versionSid = await uploadVersion(serviceSid, functionSid, source);

  console.log('Building (this can take ~30s) …');
  const buildSid = await createBuild(serviceSid, versionSid);
  await waitForBuild(serviceSid, buildSid);

  console.log('Deploying …');
  const environment = await ensureEnvironment(serviceSid);
  await deployBuild(serviceSid, environment.sid, buildSid);

  const functionUrl = `https://${environment.domain_name}${FUNCTION_PATH}`;
  console.log(`Pointing SIP domain VoiceUrl → ${functionUrl}`);
  await pointDomainAtFunction(domainSid, functionUrl);

  console.log('\nDone.');
  console.log(`  SIP domain : ${DOMAIN} (${domainSid})`);
  console.log(`  Voice URL  : ${functionUrl}  (POST, signature-protected)`);
  console.log('  Registered endpoints now bridge outbound calls to the PSTN.');
}

main().catch((err) => {
  console.error(`\nFailed: ${err.message}`);
  process.exit(1);
});
