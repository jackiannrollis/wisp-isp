package com.corebalt.isp.network.application;

import com.corebalt.isp.network.api.RouterOnboardingRequest;
import com.corebalt.isp.network.api.RouterOnboardingScriptDto;
import com.corebalt.isp.network.domain.Router;
import com.corebalt.isp.network.domain.SecretGenerator;
import com.corebalt.isp.network.domain.WireGuardKeys;
import com.corebalt.isp.network.infrastructure.RadiusCredentials;
import com.corebalt.isp.network.infrastructure.RouterRepository;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.web.InvalidRequestException;
import com.corebalt.isp.shared.web.NotFoundException;
import com.corebalt.isp.shared.web.ServiceUnavailableException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A router's onboarding script (docs/06 Router onboarding script; ADR 0013): the template
 * {@code routeros/onboarding.rsc.tmpl} filled with the hub as routers reach it, the ISP's addressing
 * on the router, and fresh secrets. Each generation makes a new WireGuard key pair (the public key is
 * kept, the private key is in the script only), a new RADIUS secret (ADR 0009; FreeRADIUS loads it at
 * its next reload) and a new REST password (kept encrypted for the tunnel worker). The script is
 * returned once and never stored.
 */
@Service
public class RouterOnboarding {

    static final String REST_USERNAME = "corebalt-api";
    private static final String TEMPLATE = "routeros/onboarding.rsc.tmpl";
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z0-9_]+)}}");
    private static final Pattern WIREGUARD_KEY = Pattern.compile("[A-Za-z0-9+/]{43}=");

    private final RouterRepository routers;
    private final RouterService routerService;
    private final RadiusCredentials credentials;
    private final AuditTrail audit;
    private final Clock clock;
    private final String hubPublicKeyFile;
    private final String hubEndpoint;
    private final int hubPort;
    private final String hubAddress;
    private final String portalAddress;
    private final int payPagePort;
    private final String ntpServers;
    private final String template;

    RouterOnboarding(
            final RouterRepository routers,
            final RouterService routerService,
            final RadiusCredentials credentials,
            final AuditTrail audit,
            final Clock clock,
            @Value("${isp.network.onboarding.hub-a.public-key-file:}") final String hubPublicKeyFile,
            @Value("${isp.network.onboarding.hub-a.endpoint}") final String hubEndpoint,
            @Value("${isp.network.onboarding.hub-a.port}") final int hubPort,
            @Value("${isp.network.onboarding.hub-a.address}") final String hubAddress,
            @Value("${isp.network.onboarding.portal-address}") final String portalAddress,
            @Value("${isp.network.onboarding.pay-page-port}") final int payPagePort,
            @Value("${isp.network.onboarding.ntp-servers}") final String ntpServers) {
        this.routers = routers;
        this.routerService = routerService;
        this.credentials = credentials;
        this.audit = audit;
        this.clock = clock;
        this.hubPublicKeyFile = hubPublicKeyFile;
        this.hubEndpoint = hubEndpoint;
        this.hubPort = hubPort;
        this.hubAddress = hubAddress;
        this.portalAddress = portalAddress;
        this.payPagePort = payPagePort;
        this.ntpServers = ntpServers;
        this.template = readTemplate();
    }

    @Transactional
    public RouterOnboardingScriptDto generate(final UUID routerId, final RouterOnboardingRequest request) {
        if (request.poolFull().equals(request.poolRestricted())) {
            throw InvalidRequestException.of("poolRestricted", "must differ from the full pool: the restricted-mode rules match it");
        }
        final InetAddress pppLocal = RouterService.ipv4(request.pppLocalAddress());
        // N10: a retired router gets no new keys or secrets.
        routers.findById(routerId).orElseThrow(() -> new NotFoundException("Router")).requireActive();
        final String hubKey = hubPublicKey();
        final Hub hub = Hub.parse(hubAddress);

        final WireGuardKeys.Pair keys = WireGuardKeys.generate();
        final String radiusSecret = credentials.regenerateRouterSecret(routerId);
        final String restPassword = SecretGenerator.restPassword();
        final String snmpCommunity = SecretGenerator.snmpCommunity();
        // First: it rewrites the monitoring columns with SQL and refreshes the entity (status UNKNOWN;
        // the worker polls with the new login once the router has run the script).
        routerService.setRestCredential(routerId, REST_USERNAME, restPassword);
        final Router router = routers.findById(routerId).orElseThrow(() -> new NotFoundException("Router"));
        router.onboard(keys.publicKey(), request.pppoeInterface(), pppLocal, request.poolFull(), request.poolRestricted(),
                request.managementCidrs(), clock.instant());
        routers.saveAndFlush(router);

        final Map<String, String> values = new LinkedHashMap<>();
        values.put("ROUTER_NAME", router.getName());
        values.put("ROUTER_ID", router.getId().toString());
        values.put("HUB_A_PUBLIC_KEY", hubKey);
        values.put("HUB_A_ENDPOINT", hubEndpoint);
        values.put("HUB_A_PORT", Integer.toString(hubPort));
        values.put("HUB_A_TUNNEL_IP", hub.address());
        values.put("WG_PRIVATE_KEY", keys.privateKey());
        values.put("TUNNEL_ADDRESS", router.getTunnelIpA().getHostAddress() + "/" + hub.prefix());
        values.put("TUNNEL_IP", router.getTunnelIpA().getHostAddress());
        values.put("RADIUS_SECRET", radiusSecret);
        values.put("FULL_POOL", request.poolFull());
        values.put("RESTRICTED_POOL", request.poolRestricted());
        values.put("PPP_LOCAL_ADDRESS", pppLocal.getHostAddress());
        values.put("PPPOE_INTERFACE", request.pppoeInterface());
        values.put("PORTAL_ADDRESS", portalAddress);
        values.put("PAYPAGE_PORT", Integer.toString(payPagePort));
        values.put("REST_PASSWORD", restPassword);
        values.put("SNMP_COMMUNITY", snmpCommunity);
        values.put("MANAGEMENT_ALLOWED", hub.network() + "," + request.managementCidrs());
        values.put("NTP_SERVERS", ntpServers);
        final String script = render(values);

        audit.record(AuditEntry.of("network.onboarding_script_generated", "router", router.getId(),
                Map.of("wgPublicKey", keys.publicKey(), "pppoeInterface", request.pppoeInterface(),
                        "poolFull", request.poolFull(), "poolRestricted", request.poolRestricted())));
        return new RouterOnboardingScriptDto(RouterService.toDto(router), fileName(router.getName()), script);
    }

    private String render(final Map<String, String> values) {
        final Matcher matcher = PLACEHOLDER.matcher(template);
        final StringBuilder out = new StringBuilder(template.length() + 512);
        while (matcher.find()) {
            final String value = values.get(matcher.group(1));
            if (value == null) {
                throw new IllegalStateException("onboarding template: no value for " + matcher.group(1));
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The hub's public key, read now: a key restored on the host after a rebuild is picked up at once. */
    private String hubPublicKey() {
        if (hubPublicKeyFile == null || hubPublicKeyFile.isBlank()) {
            throw new ServiceUnavailableException(
                    "The hub's public key is not configured on this host (ISP_HUB_A_PUBLIC_KEY_FILE).");
        }
        final String key;
        try {
            key = Files.readString(Path.of(hubPublicKeyFile)).strip();
        } catch (final IOException e) {
            throw new ServiceUnavailableException(
                    "The hub's public key is not readable on this host: is the hub running and its key file mounted?");
        }
        if (!WIREGUARD_KEY.matcher(key).matches()) {
            throw new ServiceUnavailableException("The hub's public key file does not hold a WireGuard key.");
        }
        return key;
    }

    private static String readTemplate() {
        try (InputStream in = RouterOnboarding.class.getClassLoader().getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + TEMPLATE);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("cannot read " + TEMPLATE, e);
        }
    }

    static String fileName(final String routerName) {
        final String slug = routerName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return "corebalt-" + (slug.isEmpty() ? "router" : slug) + ".rsc";
    }

    /** The hub's tunnel address with its prefix ({@code 10.200.0.1/24}): the address, the prefix and the network. */
    record Hub(String address, int prefix, String network) {
        static Hub parse(final String cidr) {
            final String[] parts = cidr == null ? new String[0] : cidr.strip().split("/");
            if (parts.length != 2 || !parts[1].matches("\\d{1,2}")) {
                throw new ServiceUnavailableException(
                        "The hub's address is misconfigured on this host (ISP_HUB_A_ADDRESS, e.g. 10.200.0.1/24).");
            }
            final int prefix = Integer.parseInt(parts[1]);
            if (prefix < 8 || prefix > 30) {
                throw new ServiceUnavailableException("The hub's address prefix must be between 8 and 30 (ISP_HUB_A_ADDRESS).");
            }
            final byte[] ip = RouterService.ipv4(parts[0]).getAddress();
            final int bits = ((ip[0] & 255) << 24) | ((ip[1] & 255) << 16) | ((ip[2] & 255) << 8) | (ip[3] & 255);
            final int net = bits & (int) (0xFFFFFFFFL << (32 - prefix));
            final String network = ((net >>> 24) & 255) + "." + ((net >>> 16) & 255) + "." + ((net >>> 8) & 255) + "."
                    + (net & 255) + "/" + prefix;
            return new Hub(parts[0], prefix, network);
        }
    }
}
