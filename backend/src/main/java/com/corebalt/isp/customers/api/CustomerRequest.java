package com.corebalt.isp.customers.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Create or replace a customer's profile. {@code phone} may be in a Kenyan national form
 * ({@code 0712 345 678}); it is stored in E.164.
 */
public record CustomerRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 32) String phone,
        @Email @Size(max = 254) String email,
        @Size(max = 500) String address,
        @Size(max = 500) String installationLocation,
        @DecimalMin("-90") @DecimalMax("90") @Digits(integer = 3, fraction = 6) BigDecimal lat,
        @DecimalMin("-180") @DecimalMax("180") @Digits(integer = 3, fraction = 6) BigDecimal lng) {}
