package com.corebalt.isp.payments.infrastructure;

import com.corebalt.isp.payments.infrastructure.daraja.DarajaClient.DarajaException;
import com.corebalt.isp.shared.web.ProblemResponseWriter;
import com.corebalt.isp.shared.web.ProblemTypes;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A provider rejection is an upstream error; never expose its raw payload, URL, or credential. */
@RestControllerAdvice(assignableTypes = MpesaController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class DarajaExceptionHandler {
    @ExceptionHandler(DarajaException.class)
    ProblemDetail refused(final DarajaException error) {
        final String code = error.errorCode();
        final String detail = "500.003.1001".equals(code)
                ? "The payment provider could not complete the request (code 500.003.1001). Try again."
                : code != null && code.matches("[0-9]{3}\\.[0-9]{3}\\.[0-9]{2,4}")
                ? "The payment provider rejected the request (code " + code + "). Check the M-Pesa account settings."
                : "The payment provider could not complete the request. Check the M-Pesa account settings and try again.";
        return ProblemResponseWriter.problem(HttpStatus.BAD_GATEWAY, ProblemTypes.PAYMENT_PROVIDER_ERROR, detail);
    }
}
