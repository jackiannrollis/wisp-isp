import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
import { IconSearch, TablerIconComponent } from '@tabler/icons-angular';
import { catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';

import { errorMessage, newIdempotencyKey } from '../../core/api/requests';
import { formatDateTime, formatMoney } from '../../shared/ui/format';
import { CustomersService } from '../customers/data/customers.service';
import { Payment, PaymentsService, SuspenseItem } from './data/payments.service';

interface Candidate {
  id: string;
  number: string;
  name: string;
  likely: boolean;
}

/**
 * Assign an unmatched payment to a customer (P3): the shortlist of customers whose number fits the
 * masked payer number comes first, as a hint only, never an automatic match; any other customer can be
 * found by number, name or phone. The assignment is audited and credits the wallet (P4, P5).
 */
@Component({
  selector: 'app-assign-dialog',
  imports: [FormsModule, MatDialogModule, MatButtonModule, MatRadioModule, TablerIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>Assign payment {{ data.item.reference }}</h2>
    <mat-dialog-content>
      <dl class="facts">
        <div>
          <dt>Amount</dt>
          <dd class="strong">{{ money(data.item.amount) }}</dd>
        </div>
        <div>
          <dt>Received</dt>
          <dd>{{ date(data.item.receivedAt) }}</dd>
        </div>
        <div>
          <dt>Account typed</dt>
          <dd>{{ data.item.accountRef || '—' }}</dd>
        </div>
        <div>
          <dt>Payer</dt>
          <dd>{{ data.item.payerName || '—' }} · {{ data.item.payerNumber || '—' }}</dd>
        </div>
      </dl>

      <label class="search">
        <tabler-icon [icon]="searchIcon" [size]="16" class="search-icon" />
        <input
          type="search"
          placeholder="Find a customer by number, name or phone"
          aria-label="Find a customer"
          [ngModel]="query()"
          (ngModelChange)="query.set($event)"
        />
      </label>

      <mat-radio-group
        class="candidates"
        aria-label="Customer to assign to"
        [ngModel]="selected()"
        (ngModelChange)="selected.set($event)"
      >
        @for (c of candidates(); track c.id) {
          <mat-radio-button [value]="c.id">
            <span class="number">{{ c.number }}</span> {{ c.name }}
            @if (c.likely) {
              <span class="hint">number fits the payer</span>
            }
          </mat-radio-button>
        } @empty {
          <p class="muted">
            @if (query().trim().length < 2) {
              No likely payers. Search for the customer above.
            } @else {
              No customer matches.
            }
          </p>
        }
      </mat-radio-group>

      @if (error(); as error) {
        <p class="error" role="alert">{{ error }}</p>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="button" [disabled]="!selected() || busy()" (click)="assign()">
        Assign to {{ selectedNumber() || 'customer' }}
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .facts {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 0.5rem 1.5rem;
      margin: 0.25rem 0 1rem;
      padding: 0.75rem 1rem;
      border: var(--app-border);
      border-radius: var(--app-radius-s);
      background: var(--mat-sys-surface-container-low);
    }
    dt {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    dd {
      margin: 0;
    }
    .strong {
      font-weight: 600;
    }
    .search {
      position: relative;
      display: flex;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    .search input {
      width: 100%;
      height: 2.25rem;
      padding: 0 0.75rem 0 2rem;
      border: var(--app-border);
      border-radius: var(--app-radius-s);
      background: var(--mat-sys-surface);
      color: var(--mat-sys-on-surface);
      font: var(--mat-sys-body-medium);
    }
    .search input:focus {
      outline: none;
      border-color: var(--mat-sys-primary);
    }
    .search-icon {
      position: absolute;
      left: 0.625rem;
      color: var(--mat-sys-on-surface-variant);
    }
    .candidates {
      display: flex;
      flex-direction: column;
      min-height: 6rem;
      max-height: 16rem;
      overflow-y: auto;
    }
    .number {
      font-weight: 600;
    }
    .hint {
      margin-left: 0.5rem;
      font: var(--mat-sys-label-small);
      color: var(--app-warning);
    }
    .error {
      color: var(--mat-sys-error);
    }
    mat-dialog-content {
      min-width: min(30rem, 80vw);
    }
  `,
})
export class AssignDialogComponent {
  protected readonly data = inject<{ item: SuspenseItem }>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<AssignDialogComponent, Payment>>(MatDialogRef);
  private readonly payments = inject(PaymentsService);
  private readonly customers = inject(CustomersService);
  private readonly key = newIdempotencyKey();

  protected readonly money = formatMoney;
  protected readonly date = formatDateTime;
  protected readonly searchIcon = IconSearch;
  protected readonly query = signal('');
  protected readonly selected = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly found = toSignal(
    toObservable(this.query).pipe(
      debounceTime(250),
      map((q) => q.trim()),
      distinctUntilChanged(),
      switchMap((q) =>
        q.length < 2
          ? of([])
          : this.customers.list({ page: 0, size: 8, filters: { status: 'ACTIVE' }, q }).pipe(
              map((page) => page.items),
              catchError(() => of([])),
            ),
      ),
    ),
    { initialValue: [] },
  );

  /** Likely payers first, then search results not already listed. */
  protected readonly candidates = computed<Candidate[]>(() => {
    const likely = (this.data.item.likelyPayers ?? []).map((c) => ({
      id: c.id ?? '',
      number: c.number ?? '',
      name: c.name ?? '',
      likely: true,
    }));
    const seen = new Set(likely.map((c) => c.id));
    const found = this.found()
      .filter((c) => !seen.has(c.id ?? ''))
      .map((c) => ({ id: c.id ?? '', number: c.number ?? '', name: c.name ?? '', likely: false }));
    return [...likely, ...found];
  });

  protected readonly selectedNumber = computed(
    () => this.candidates().find((c) => c.id === this.selected())?.number ?? null,
  );

  protected assign(): void {
    const customerId = this.selected();
    if (!customerId) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.payments.assign(this.data.item.id ?? '', customerId, this.key).subscribe({
      next: (payment) => this.ref.close(payment),
      error: (e: unknown) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
