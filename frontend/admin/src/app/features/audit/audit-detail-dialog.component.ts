import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { Router, RouterLink } from '@angular/router';

import { formatDateTime } from '../../shared/ui/format';
import { actionLabel, actorLabel, diff, targetLabel, targetLink, targetNoun } from './audit-labels';
import { AuditRow } from './data/audit.service';

/**
 * One audit row (A1): who, what, when, why, and the before and after values side by side. From here
 * staff can narrow the log to the same target or the same person.
 */
@Component({
  selector: 'app-audit-detail-dialog',
  imports: [MatDialogModule, MatButtonModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ action(row.action) }}</h2>
    <mat-dialog-content>
      <dl class="facts">
        <div>
          <dt>When</dt>
          <dd>{{ date(row.occurredAt) }}</dd>
        </div>
        <div>
          <dt>Who</dt>
          <dd>{{ actor(row) }}</dd>
        </div>
        <div>
          <dt>About</dt>
          <dd>
            @if (link; as link) {
              <a [routerLink]="link" mat-dialog-close>{{ target(row.targetType) }}</a>
            } @else {
              {{ target(row.targetType) || '—' }}
            }
          </dd>
        </div>
        <div>
          <dt>Reason</dt>
          <dd>{{ row.reason || '—' }}</dd>
        </div>
      </dl>

      @if (changes().length) {
        <table class="changes">
          <thead>
            <tr>
              <th>Field</th>
              <th>Before</th>
              <th>After</th>
            </tr>
          </thead>
          <tbody>
            @for (c of changes(); track c.field) {
              <tr [class.same]="!c.changed">
                <td class="field">{{ c.field }}</td>
                <td>{{ c.before || '—' }}</td>
                <td class="after">{{ c.after || '—' }}</td>
              </tr>
            }
          </tbody>
        </table>
      } @else {
        <p class="muted">No values recorded for this action.</p>
      }
      <p class="ref muted">Action {{ row.action }} · reference {{ row.correlationId || row.id }}</p>
    </mat-dialog-content>
    <mat-dialog-actions>
      @if (row.targetId) {
        <button mat-button type="button" (click)="narrow('target')">
          Everything about this {{ noun(row.targetType) }}
        </button>
      }
      @if (row.actorId && row.actorType === 'STAFF') {
        <button mat-button type="button" (click)="narrow('actor')">
          Everything by {{ row.actorName }}
        </button>
      }
      <span class="spacer"></span>
      <button mat-flat-button type="button" mat-dialog-close>Close</button>
    </mat-dialog-actions>
  `,
  styles: `
    .facts {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem 1.5rem;
      margin: 0 0 1rem;
    }
    dt {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    dd {
      margin: 0;
    }
    .changes {
      width: 100%;
      border-collapse: collapse;
      border: var(--app-border);
      border-radius: var(--app-radius-s);
    }
    th {
      text-align: start;
      font: var(--mat-sys-label-medium);
      font-weight: 600;
      color: var(--mat-sys-on-surface-variant);
      background: var(--mat-sys-surface-container-low);
      padding: 0.5rem 0.75rem;
      border-bottom: var(--app-border);
    }
    td {
      padding: 0.5rem 0.75rem;
      border-bottom: var(--app-border);
      vertical-align: top;
      word-break: break-word;
    }
    tbody tr:last-child td {
      border-bottom: none;
    }
    .field {
      font-weight: 600;
      white-space: nowrap;
    }
    .after {
      font-weight: 600;
    }
    .same td {
      color: var(--mat-sys-on-surface-variant);
      font-weight: 400;
    }
    .muted {
      color: var(--mat-sys-on-surface-variant);
    }
    .ref {
      margin-top: 1rem;
      font: var(--mat-sys-body-small);
    }
    .spacer {
      flex: 1;
    }
    mat-dialog-content {
      min-width: min(34rem, 80vw);
    }
  `,
})
export class AuditDetailDialogComponent {
  protected readonly row = inject<AuditRow>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<AuditDetailDialogComponent>);
  private readonly router = inject(Router);

  protected readonly action = actionLabel;
  protected readonly actor = actorLabel;
  protected readonly target = targetLabel;
  protected readonly noun = targetNoun;
  protected readonly date = formatDateTime;
  protected readonly link = targetLink(this.row);
  protected readonly changes = computed(() => diff(this.row.before, this.row.after));

  protected narrow(by: 'target' | 'actor'): void {
    const row = this.row;
    const queryParams =
      by === 'target'
        ? { targetId: row.targetId, target: targetLabel(row.targetType) }
        : { actorId: row.actorId, actor: row.actorName };
    this.ref.close();
    void this.router.navigate(['/audit-log'], { queryParams });
  }
}
