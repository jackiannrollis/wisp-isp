package com.corebalt.isp.network.api;

import java.util.Optional;
import java.util.UUID;

/** A router's display name for other modules (staff alerts); current tenant only. */
public interface RouterNames {

    Optional<String> name(UUID routerId);
}
