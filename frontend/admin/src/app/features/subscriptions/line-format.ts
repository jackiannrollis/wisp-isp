import { formatBytes } from '../../shared/ui/format';
import { accessReasonLabel, statusLabel } from '../../shared/ui/status-chip.component';
import { Line } from './data/lines.service';

/** The access profile with the reason it is limited: `Throttled · fair use`. */
export function accessText(line: Line): string {
  const reason = accessReasonLabel(line.accessReason);
  return reason
    ? `${statusLabel(line.accessProfile)} · ${reason}`
    : statusLabel(line.accessProfile);
}

/** Usage in the cycle against the allowance or cap (N9): `12.5 GB of 100 GB`; a dash while not metered. */
export function usageText(line: Line): string {
  const usage = line.usage;
  if (!usage?.cycleStartedAt) {
    return '—';
  }
  const limit = usage.fairUseBytes ?? usage.dataCapBytes;
  return limit
    ? `${formatBytes(usage.usedBytes)} of ${formatBytes(limit)}`
    : formatBytes(usage.usedBytes);
}
