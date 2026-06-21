package com.elitale.coldbirds.coldcalling.storage.sqlite;

import com.elitale.coldbirds.coldcalling.domain.value.CallDisposition;
import com.elitale.coldbirds.coldcalling.domain.value.NumberReputation;
import com.elitale.coldbirds.coldcalling.domain.value.SmsStatus;
import java.time.Instant;

/** Package-private serialization helpers for sealed domain types. */
final class DomainMappers {

    private DomainMappers() {}

    // ── CallDisposition ──────────────────────────────────────────────────────

    static String dispositionToString(CallDisposition d) {
        return switch (d) {
            case CallDisposition.Interested i    -> "interested";
            case CallDisposition.NotInterested n -> "not_interested";
            case CallDisposition.Voicemail v     -> "voicemail";
            case CallDisposition.NoAnswer na     -> "no_answer";
            case CallDisposition.Busy b          -> "busy";
            case CallDisposition.DNC dnc         -> "dnc";
            case CallDisposition.Callback cb     -> "callback:" + cb.scheduledAt().toEpochMilli();
            case CallDisposition.Failed f        -> "failed:" + f.reason();
        };
    }

    static CallDisposition dispositionFromString(String s) {
        if (s == null) throw new IllegalArgumentException("disposition string is null");
        return switch (s) {
            case "interested"     -> new CallDisposition.Interested();
            case "not_interested" -> new CallDisposition.NotInterested();
            case "voicemail"      -> new CallDisposition.Voicemail();
            case "no_answer"      -> new CallDisposition.NoAnswer();
            case "busy"           -> new CallDisposition.Busy();
            case "dnc"            -> new CallDisposition.DNC();
            default               -> {
                if (s.startsWith("callback:")) {
                    long ms = Long.parseLong(s.substring("callback:".length()));
                    yield new CallDisposition.Callback(Instant.ofEpochMilli(ms));
                }
                if (s.startsWith("failed:")) {
                    yield new CallDisposition.Failed(s.substring("failed:".length()));
                }
                throw new IllegalArgumentException("Unknown disposition string: " + s);
            }
        };
    }

    // ── SmsStatus ────────────────────────────────────────────────────────────

    static String smsStatusToString(SmsStatus s) {
        return switch (s) {
            case SmsStatus.Pending p    -> "pending";
            case SmsStatus.Delivered d  -> "delivered";
            case SmsStatus.Failed f     -> "failed:" + f.reason();
        };
    }

    static SmsStatus smsStatusFromString(String s) {
        if (s == null) throw new IllegalArgumentException("smsStatus string is null");
        return switch (s) {
            case "pending"   -> new SmsStatus.Pending();
            case "delivered" -> new SmsStatus.Delivered();
            default          -> {
                if (s.startsWith("failed:")) {
                    yield new SmsStatus.Failed(s.substring("failed:".length()));
                }
                throw new IllegalArgumentException("Unknown smsStatus string: " + s);
            }
        };
    }

    // ── NumberReputation ─────────────────────────────────────────────────────

    static String reputationToString(NumberReputation r) {
        return switch (r) {
            case NumberReputation.Clean c      -> "clean";
            case NumberReputation.Warning w    -> "warning:" + w.reason();
            case NumberReputation.Flagged f    -> "flagged:" + f.reason();
        };
    }

    static NumberReputation reputationFromString(String s) {
        if (s == null) throw new IllegalArgumentException("reputation string is null");
        return switch (s) {
            case "clean" -> new NumberReputation.Clean();
            default      -> {
                if (s.startsWith("warning:")) {
                    yield new NumberReputation.Warning(s.substring("warning:".length()));
                }
                if (s.startsWith("flagged:")) {
                    yield new NumberReputation.Flagged(s.substring("flagged:".length()));
                }
                throw new IllegalArgumentException("Unknown reputation string: " + s);
            }
        };
    }

    // ── Tags (JSON array of strings) ─────────────────────────────────────────

    static String tagsToJson(java.util.List<String> tags) {
        if (tags.isEmpty()) return "[]";
        var sb = new StringBuilder("[");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"');
            sb.append(tags.get(i).replace("\\", "\\\\").replace("\"", "\\\""));
            sb.append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    static java.util.List<String> jsonToTags(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) return java.util.List.of();
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return java.util.List.copyOf(
                    mapper.readValue(json, java.util.List.class));
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
}
