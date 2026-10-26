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

/** Gate 1 with every count present (the API's fields are optional in the generated types). */
export interface Gate1View {
  matching: { automatic: number; byStaff: number; unmatched: number; percent: string };
  restores: {
    total: number;
    completed: number;
    pending: number;
    missed: number;
    slower: number;
    p95: string;
    max: string;
  };
  reconnects: { coaFailed: number; routerOffline: number; missed: number };
  results: { matching: string; restores: string; reconnects: string };
}

export function gate1View(g: Gate1): Gate1View {
  return {
    matching: {
      automatic: g.matching?.automatic ?? 0,
      byStaff: g.matching?.byStaff ?? 0,
      unmatched: g.matching?.unmatched ?? 0,
      percent: formatPercent(g.matching?.automaticPercent),
    },
    restores: {
      total: g.restores?.total ?? 0,
      completed: g.restores?.completed ?? 0,
      pending: g.restores?.pending ?? 0,
      missed: g.restores?.missed ?? 0,
      slower: g.restores?.slowerThan60s ?? 0,
      p95: formatSeconds(g.restores?.p95Seconds),
      max: formatSeconds(g.restores?.maxSeconds),
    },
    reconnects: {
      coaFailed: g.reconnects?.coaFailedAlerts ?? 0,
      routerOffline: g.reconnects?.routerOfflineAlerts ?? 0,
      missed: g.reconnects?.missedRestores ?? 0,
    },
    results: {
      matching: criterion(g.targets?.matchingAbove98),
      restores: criterion(g.targets?.noRestoreSlowerThan60s),
      reconnects: criterion(g.targets?.noReconnectsNeedingHands),
    },
  };
}

/**
 * Gate 1 of one tenant (docs/09, ADR 0018) as the platform measured it: payment matching, the time from
 * a callback to the restore of a RESTRICTED line, and the reconnects that needed hands. Each criterion
 * says met, not met, or that there is nothing to judge yet; the counts behind it follow.
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
