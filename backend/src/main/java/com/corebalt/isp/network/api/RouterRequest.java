package com.corebalt.isp.network.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Register a router by its tunnel address on hub A (an IPv4 address such as {@code 10.200.0.12}). */
public record RouterRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Pattern(regexp = "\\d{1,3}(\\.\\d{1,3}){3}", message = "must be an IPv4 address")
                String tunnelIp) {}
