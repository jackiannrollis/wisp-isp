import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';

import { criterion, formatPercent, formatSeconds } from '../../shared/format';
import { Gate1 } from '../../shared/models';
import { StatusChipComponent } from '../../shared/status-chip.component';
import { TenantsService } from './tenants.service';

/** The periods offered; two monthly billing cycles (docs/09 Gate 1) is the default. */
export const GATE1_PERIODS = [7, 30, 60, 90] as const;

/** What a payment lifted, as the API names it, and how the console says it. */
const KIND_LABELS: Record<string, string> = {
  CUT_OFF: 'Cut off',
  GRACE_THROTTLE: 'Grace throttle',
  FAIR_USE: 'Fair use',
};

export interface RestoreRow {
  kind: string;
  total: number;
  slower: number;
  missed: number;
  pending: number;
  p95: string;
  max: string;
}

/** Gate 1 with every count present (the API's fields are optional in the generated types). */
export interface Gate1View {
  matching: { automatic: number; byStaff: number; unmatched: number; percent: string };
  restores: RestoreRow;
  /** All kinds first, then one row per kind. */
  rows: RestoreRow[];
  reconnects: {
    staffDisconnects: number;
    paidOffline: number;
    missed: number;
    coaFailed: number;
    routerOffline: number;
  };
  results: { matching: string; restores: string; reconnects: string };
}

function restoreRow(
  kind: string,
  r:
    | {
        total?: number;
        completed?: number;
        pending?: number;
        missed?: number;
        slowerThan60s?: number;
        p95Seconds?: number;
        maxSeconds?: number;
      }
    | undefined,
): RestoreRow {
  return {
    kind,
    total: r?.total ?? 0,
    slower: r?.slowerThan60s ?? 0,
    missed: r?.missed ?? 0,
    pending: r?.pending ?? 0,
    p95: formatSeconds(r?.p95Seconds),
    max: formatSeconds(r?.maxSeconds),
  };
}

export function gate1View(g: Gate1): Gate1View {
  const restores = restoreRow('All', g.restores);
  return {
    matching: {
      automatic: g.matching?.automatic ?? 0,
      byStaff: g.matching?.byStaff ?? 0,
      unmatched: g.matching?.unmatched ?? 0,
      percent: formatPercent(g.matching?.automaticPercent),
    },
    restores,
    rows: [
      restores,
      ...(g.restoresByKind ?? []).map((k) =>
        restoreRow(KIND_LABELS[k.kind ?? ''] ?? k.kind ?? '—', k),
      ),
    ],
    reconnects: {
      staffDisconnects: g.reconnects?.staffDisconnects ?? 0,
      paidOffline: g.reconnects?.paidOfflineReports ?? 0,
      missed: g.reconnects?.missedRestores ?? 0,
      coaFailed: g.reconnects?.coaFailedAlerts ?? 0,
      routerOffline: g.reconnects?.routerOfflineAlerts ?? 0,
    },
    results: {
      matching: criterion(g.targets?.matchingAbove98),
      restores: criterion(g.targets?.noRestoreSlowerThan60s),
      reconnects: criterion(g.targets?.noManualReconnects),
    },
  };
}

/**
 * Gate 1 of one tenant (docs/09, ADR 0018) as the platform measured it: payment matching, the time from
 * a callback to a line lifted to full speed (from cut off, a grace throttle or a fair-use throttle), and
 * the manual reconnects (staff disconnects after a payment, "paid but still offline" reports, missed
 * restores, reconnect alerts). Each criterion says met, not met, or that there is nothing to judge yet.
 */
@Component({
  selector: 'app-gate1-panel',
  imports: [MatButtonModule, MatButtonToggleModule, StatusChipComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './gate1-panel.component.html',
  styleUrl: './tenants.scss',
})
export class Gate1PanelComponent {
  readonly tenantId = input.required<string>();

  private readonly service = inject(TenantsService);

  protected readonly periods = GATE1_PERIODS;
  protected readonly days = signal<number>(60);

  protected readonly gate1 = rxResource({
    params: () => ({ id: this.tenantId(), days: this.days() }),
    stream: ({ params }) => this.service.gate1(params.id, params.days),
  });
  protected readonly view = computed(() => {
    const g = this.gate1.value();
    return g ? gate1View(g) : null;
  });
}
