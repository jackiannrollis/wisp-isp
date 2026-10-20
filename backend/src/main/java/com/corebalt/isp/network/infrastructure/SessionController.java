package com.corebalt.isp.network.infrastructure;

import com.corebalt.isp.network.api.OpenSessionDto;
import com.corebalt.isp.network.application.SessionViews;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Who is online now, across the tenant's routers (docs/06 Accounting); any staff member may read it. */
@RestController
@RequestMapping("/api/v1/sessions")
@Tag(name = "Network")
class SessionController {

    private final SessionViews sessions;

    SessionController(final SessionViews sessions) {
        this.sessions = sessions;
    }

    @GetMapping
    @Operation(summary = "Open sessions in radacct; routerId narrows to one router, q matches the username's start."
            + " onRouter compares with the router's last /ppp/active poll (ADR 0011)")
    PageResponse<OpenSessionDto> list(
            @RequestParam(required = false) final UUID routerId,
            @RequestParam(required = false) final String q,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "50") final int size,
            @RequestParam(required = false) final String sort) {
        return sessions.openSessions(routerId, q == null || q.isBlank() ? null : q.strip(),
                PageRequests.of(page, size, sort, SessionViews.SESSION_SORT.keySet(), Sort.by("username")));
    }
}
