package com.corebalt.isp.network.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The ISP's addressing on one router, for its onboarding script (docs/06): the interface customers
 * dial on, the PPPoE server's own address, the pools FULL and RESTRICTED sessions get addresses from
 * (RouterOS ranges or CIDRs; they must differ, the restricted-mode rules match the list), and the
 * networks that may reach the router's REST service beside the hub's.
 */
public record RouterOnboardingRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,32}", message = "a RouterOS interface name") String pppoeInterface,
        @NotBlank @Pattern(regexp = "\\d{1,3}(\\.\\d{1,3}){3}", message = "must be an IPv4 address") String pppLocalAddress,
        @NotBlank @Pattern(regexp = POOL, message = "an address range (10.10.0.10-10.10.0.250) or a network (10.10.0.0/24)")
                String poolFull,
        @NotBlank @Pattern(regexp = POOL, message = "an address range (10.11.0.10-10.11.0.250) or a network (10.11.0.0/24)")
                String poolRestricted,
        @NotBlank @Size(max = 200) @Pattern(regexp = CIDRS, message = "comma-separated IPv4 networks or addresses")
                String managementCidrs) {

    static final String IPV4 = "\\d{1,3}(\\.\\d{1,3}){3}";
    static final String POOL = IPV4 + "(-" + IPV4 + "|/\\d{1,2})";
    static final String CIDRS = IPV4 + "(/\\d{1,2})?(," + IPV4 + "(/\\d{1,2})?)*";
}
