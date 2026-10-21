-- Fair use (docs/09 step 8b): a line's allowance counts from its last renewal (Q12 default) and only a
-- renewal resets it (Q13 default). cycle_started_at is that instant, written in the renewal
-- transaction (first activation included), so the RADIUS view sees a new cycle the moment a renewal
-- commits, whatever the roll-up job or the broker are doing (E4).
--
-- Null means the cycle start is unknown (lines renewed before this migration): such a line is not
-- metered until its next renewal. No backfill here (conventions.md: no data migration mixed with
-- schema changes); guessing a start for a line with pauses or early renewals could throttle a customer
-- wrongly.
ALTER TABLE subscriptions_subscription ADD COLUMN cycle_started_at timestamptz;
