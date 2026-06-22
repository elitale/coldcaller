/**
 * Twilio Serverless Function — bridges a SIP-registered endpoint's outbound
 * call to the PSTN.
 *
 * Wired as the Voice webhook ("A call comes in") on the Programmable Voice SIP
 * Domain. When the coldCalling desktop app (a registered softphone) dials
 * sip:+1XXXXXXXXXX@<domain>, Twilio invokes this handler with the SIP To/From
 * delivered as URIs (e.g. "sip:+12025550142@elitale.sip.twilio.com"). We strip
 * each URI to its E.164 user part and <Dial><Number> the destination, using the
 * caller's own number as the caller ID so the app's number rotation still
 * controls what the callee sees.
 *
 * Visibility is "protected": Twilio signs Voice webhook requests and the
 * Functions runtime validates that signature, so only Twilio can invoke this.
 * Do NOT switch to "public" — an open URL that places PSTN calls is a
 * toll-fraud risk.
 *
 * Bundled as a classpath resource and deployed per-account by
 * TwilioVoiceBridgeProvisioner. Keep in sync with
 * .scripts/sip-pstn-handler/pstn-bridge.js (the operator/CLI fallback).
 */
exports.handler = function handler(context, event, callback) {
  const response = new Twilio.twiml.VoiceResponse();

  // SIP URI -> user part. "sip:+1202...@host;transport=tls?h=v" -> "+1202..."
  const userPart = (uri) =>
    String(uri || '')
      .trim()
      .replace(/^sips?:/i, '')
      .split('@')[0]
      .split(';')[0]
      .split('?')[0];

  const digits = (s) => s.replace(/[^\d]/g, '');

  const destination = digits(userPart(event.To || event.Called));
  const callerDigits = digits(userPart(event.From || event.Caller));

  // A routable E.164 number is 8–15 digits. Anything shorter is a misdial;
  // reject instead of placing a bogus call.
  if (destination.length < 8 || destination.length > 15) {
    response.reject({ reason: 'rejected' });
    return callback(null, response);
  }

  const callerId =
    callerDigits.length >= 8 && callerDigits.length <= 15
      ? `+${callerDigits}`
      : context.CALLER_ID; // optional Service env-var fallback

  const dialAttributes = { answerOnBridge: true };
  if (callerId) {
    dialAttributes.callerId = callerId;
  }

  const dial = response.dial(dialAttributes);
  dial.number(`+${destination}`);

  return callback(null, response);
};
