package com.corebalt.isp.payments.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * Reads Daraja callback bodies (docs/05 APIs used). Amounts arrive as shillings ({@code "250.00"}
 * or {@code 250}) and become minor units exactly (B2); times are East Africa Time
 * {@code yyyyMMddHHmmss}.
 */
public final class MpesaCallbacks {

    static final ZoneId EAT = ZoneId.of("Africa/Nairobi");
    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private MpesaCallbacks() {}

    /** C2B confirmation, v1 or v2. */
    public static IncomingPayment c2bConfirmation(final JsonNode body, final String raw) {
        final String transId = required(body, "TransID");
        final String name = Stream.of("FirstName", "MiddleName", "LastName")
                .map(field -> text(body, field))
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));
        return new IncomingPayment(
                "MPESA_PAYBILL",
                transId,
                minorUnits(required(body, "TransAmount")),
                time(text(body, "TransTime")),
                text(body, "BillRefNumber"),
                text(body, "MSISDN"),
                name.isBlank() ? null : name,
                raw,
                null);
    }

    /** The {@code Body.stkCallback} of an STK Push result. */
    public record StkResult(
            String checkoutRequestId, String resultCode, String resultDesc, String receiptNumber,
            Long amountMinor, Instant transactionTime, String phone) {

        public boolean succeeded() {
            return "0".equals(resultCode);
        }
    }

    public static StkResult stkResult(final JsonNode body) {
        final JsonNode callback = body.path("Body").path("stkCallback");
        if (callback.isMissingNode()) {
            throw new UnreadableCallback("no Body.stkCallback");
        }
        final Map<String, String> items = new HashMap<>();
        for (final JsonNode item : callback.path("CallbackMetadata").path("Item")) {
            final JsonNode value = item.get("Value");
            items.put(item.path("Name").asString(), value == null || value.isNull() ? null : value.asString());
        }
        final String amount = items.get("Amount");
        return new StkResult(
                required(callback, "CheckoutRequestID"),
                required(callback, "ResultCode"),
                text(callback, "ResultDesc"),
                items.get("MpesaReceiptNumber"),
                amount == null ? null : minorUnits(amount),
                time(items.get("TransactionDate")),
                items.get("PhoneNumber"));
    }

    /**
     * A Transaction Status result that shows a completed payment becomes a payment (matched by the
     * usual rules, usually UNMATCHED: the result carries no account reference). Anything else is
     * recorded on the callback row only.
     */
    public static Optional<IncomingPayment> transactionStatus(final JsonNode body, final String raw) {
        final JsonNode result = body.path("Result");
        if (!"0".equals(text(result, "ResultCode"))) {
            return Optional.empty();
        }
        final Map<String, String> parameters = new HashMap<>();
        for (final JsonNode parameter : result.path("ResultParameters").path("ResultParameter")) {
            final JsonNode value = parameter.get("Value");
            parameters.put(parameter.path("Key").asString(), value == null || value.isNull() ? null : value.asString());
        }
        if (!"Completed".equalsIgnoreCase(parameters.get("TransactionStatus")) || parameters.get("ReceiptNo") == null) {
            return Optional.empty();
        }
        // DebitPartyName is "2547XXXXXXXX - JOHN DOE".
        final String debitParty = parameters.getOrDefault("DebitPartyName", "");
        final int dash = debitParty.indexOf(" - ");
        return Optional.of(new IncomingPayment(
                "MPESA_PAYBILL",
                parameters.get("ReceiptNo"),
                minorUnits(parameters.getOrDefault("Amount", "0")),
                time(parameters.get("InitiatedTime")),
                null,
                dash > 0 ? debitParty.substring(0, dash).strip() : null,
                dash > 0 ? debitParty.substring(dash + 3).strip() : null,
                raw,
                null));
    }

    static long minorUnits(final String shillings) {
        try {
            final long minor = new BigDecimal(shillings.strip()).movePointRight(2).longValueExact();
            if (minor <= 0) {
                throw new UnreadableCallback("amount must be positive");
            }
            return minor;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new UnreadableCallback("unreadable amount");
        }
    }

    static Instant time(final String eat) {
        if (eat == null || !eat.strip().matches("[0-9]{14}")) {
            return Instant.now();
        }
        return LocalDateTime.parse(eat.strip(), TIME).atZone(EAT).toInstant();
    }

    private static String required(final JsonNode node, final String field) {
        final String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw new UnreadableCallback("missing " + field);
        }
        return value.strip();
    }

    private static String text(final JsonNode node, final String field) {
        final JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    /** A callback whose content cannot be used; the message names the field, never its value. */
    public static class UnreadableCallback extends RuntimeException {

        public UnreadableCallback(final String message) {
            super(message);
        }
    }
}
