package com.corebalt.isp.notifications.infrastructure;

import com.corebalt.isp.notifications.application.NotificationQueries;
import com.corebalt.isp.notifications.application.NotificationQueue;
import com.corebalt.isp.notifications.application.NotificationQueue.TemplateView;
import com.corebalt.isp.notifications.application.SmsSettings;
import com.corebalt.isp.notifications.domain.Template;
import com.corebalt.isp.shared.web.PageRequests;
import com.corebalt.isp.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Notifications")
class NotificationController {

    private final SmsSettings settings;
    private final NotificationQueue queue;
    private final NotificationQueries queries;

    NotificationController(final SmsSettings settings, final NotificationQueue queue, final NotificationQueries queries) {
        this.settings = settings;
        this.queue = queue;
        this.queries = queries;
    }

    @PutMapping("/api/v1/settings/notifications")
    @PreAuthorize("hasAuthority('settings.secrets')")
    @Operation(summary = "Africa's Talking account (API key write-only), sender ID and sending window (M2)")
    SmsSettings.SettingsView configure(@Valid @RequestBody final SmsSettings.SettingsRequest request) {
        return settings.configure(request);
    }

    @GetMapping("/api/v1/settings/notifications")
    @PreAuthorize("hasAuthority('settings.notifications')")
    @Operation(summary = "SMS settings and the delivery-report URL to give Africa's Talking (never the API key)")
    SmsSettings.SettingsView view() {
        return settings.view();
    }

    @GetMapping("/api/v1/settings/notifications/templates")
    @PreAuthorize("hasAuthority('settings.notifications')")
    @Operation(summary = "Every template with its current wording, a sample and its SMS part count")
    List<TemplateView> templates() {
        return queue.templates();
    }

    record TemplateBody(@NotBlank @Size(max = 918) String body) {}

    @PutMapping("/api/v1/settings/notifications/templates/{key}")
    @PreAuthorize("hasAuthority('settings.notifications')")
    @Operation(summary = "Use the tenant's own wording; only listed placeholders are allowed (M3)")
    TemplateView saveTemplate(@PathVariable final Template key, @Valid @RequestBody final TemplateBody body) {
        return queue.saveTemplate(key, body.body());
    }

    @DeleteMapping("/api/v1/settings/notifications/templates/{key}")
    @PreAuthorize("hasAuthority('settings.notifications')")
    @Operation(summary = "Back to the platform default")
    TemplateView resetTemplate(@PathVariable final Template key) {
        return queue.resetTemplate(key);
    }

    @PostMapping("/api/v1/settings/notifications/templates/{key}/preview")
    @PreAuthorize("hasAuthority('settings.notifications')")
    @Operation(summary = "Render a draft with sample values and count its SMS parts (GSM-7 160/153, UCS-2 70/67)")
    TemplateView preview(@PathVariable final Template key, @Valid @RequestBody final TemplateBody body) {
        return queue.preview(key, body.body());
    }

    @GetMapping("/api/v1/notifications")
    @Operation(summary = "Messages, newest first, optionally of one customer or in one status")
    PageResponse<NotificationQueries.MessageView> messages(
            @RequestParam(required = false) final UUID customerId,
            @RequestParam(required = false) final String status,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "50") final int size) {
        return queries.messages(customerId, status, PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }

    @GetMapping("/api/v1/notifications/alerts")
    @Operation(summary = "Staff alerts: SMS problems (credit exhausted, sender ID or API key refused, dead letters), routers"
            + " offline and CoA or Disconnect failures (N5); state OPEN (default) or CLEARED")
    PageResponse<NotificationQueries.AlertView> alerts(
            @RequestParam(defaultValue = "OPEN") final String state,
            @RequestParam(defaultValue = "0") final int page,
            @RequestParam(defaultValue = "50") final int size) {
        return queries.alerts(state, PageRequests.of(page, size, null, Set.of(), Sort.unsorted()));
    }

    @GetMapping("/api/v1/notifications/phone-flags")
    @Operation(summary = "Numbers the SMS provider refused for good (invalid or blacklisted)")
    List<NotificationQueries.PhoneFlagView> phoneFlags() {
        return queries.phoneFlags();
    }

    @DeleteMapping("/api/v1/notifications/phone-flags/{phone}")
    @PreAuthorize("hasAuthority('settings.notifications')")
    @Operation(summary = "Clear a flag after the number was corrected")
    ResponseEntity<Void> clearPhoneFlag(@PathVariable final String phone) {
        queries.clearPhoneFlag(phone);
        return ResponseEntity.noContent().build();
    }
}
