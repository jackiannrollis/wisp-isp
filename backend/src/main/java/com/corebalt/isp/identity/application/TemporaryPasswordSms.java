package com.corebalt.isp.identity.application;

import com.corebalt.isp.notifications.api.OneTimeCodes;
import com.corebalt.isp.shared.audit.AuditEntry;
import com.corebalt.isp.shared.audit.AuditTrail;
import com.corebalt.isp.shared.web.ConflictException;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Optional SMS of a staff member's temporary password (Q11, resolved 2026-10-20: an explicit exception to
 * M3 for staff invitations and resets only). The password is still shown once in the response; the SMS
 * goes to the staff member's own phone, after the password is committed and outside any transaction, and
 * the attempt and its outcome are audited (A1) without the password. Expiry (72 hours) and the forced
 * change at first sign-in are those of every temporary password.
 */
@Service
public class TemporaryPasswordSms {

    private final OneTimeCodes sms;
    private final AuditTrail audit;
    private final TransactionTemplate tx;

    TemporaryPasswordSms(final OneTimeCodes sms, final AuditTrail audit, final TransactionTemplate tx) {
        this.sms = sms;
        this.audit = audit;
        this.tx = tx;
    }

    /** Refuses before anything changes when the staff member has no phone to send to. */
    public static void requirePhone(final String phone) {
        if (phone == null || phone.isBlank()) {
            throw new ConflictException("This staff member has no phone number to send the temporary password to.");
        }
    }

    public StaffAdmin.Invitation send(final StaffAdmin.Invitation invitation) {
        requirePhone(invitation.staff().phone());
        final OneTimeCodes.Delivery delivery = sms.sendTemporaryPassword(invitation.staff().phone(), invitation.temporaryPassword());
        tx.executeWithoutResult(s -> audit.record(new AuditEntry("identity.temporary_password_sms", "staff_user",
                invitation.staff().id(), null, Map.of("delivery", delivery.name()), null)));
        return invitation.withSmsDelivery(delivery.name());
    }
}
