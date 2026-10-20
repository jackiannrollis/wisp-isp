import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { IconFileUpload, TablerIconComponent } from '@tabler/icons-angular';
import { of } from 'rxjs';

import { errorMessage } from '../../core/api/requests';
import { CellDirective } from '../../shared/data-table/cell.directive';
import { DataTableComponent } from '../../shared/data-table/data-table.component';
import { ColumnDef, TableFetch } from '../../shared/data-table/data-table.types';
import { formatDateTime, formatMoney } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { PaymentsService, ReconciliationRun } from './data/payments.service';

/** A statement export larger than this is surely not an M-Pesa statement for one shortcode. */
const MAX_STATEMENT_BYTES = 10 * 1024 * 1024;

/**
 * Daily reconciliation (docs/05): upload the M-Pesa statement export; the platform compares it with
 * the payments it recorded, both ways, and lists matched payments not yet credited (P4). A transaction
 * missing on the platform can be asked about at Daraja; if it completed, it arrives as a payment.
 */
@Component({
  selector: 'app-reconciliation-page',
  imports: [
    DataTableComponent,
    CellDirective,
    PageHeaderComponent,
    MatButtonModule,
    MatSnackBarModule,
    TablerIconComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './reconciliation-page.component.html',
  styleUrl: './reconciliation-page.component.scss',
})
export class ReconciliationPageComponent {
  private readonly payments = inject(PaymentsService);
  private readonly snack = inject(MatSnackBar);

  protected readonly uploadIcon = IconFileUpload;
  protected readonly money = formatMoney;
  protected readonly date = formatDateTime;
  protected readonly uploading = signal(false);
  protected readonly version = signal(0);
  protected readonly selectedId = signal<string | null>(null);
  protected readonly asked = signal<Set<string>>(new Set());

  protected readonly fetch: TableFetch<ReconciliationRun> = (query) =>
    this.payments.reconciliations(query);
  protected readonly select = (run: ReconciliationRun) => this.selectedId.set(run.id ?? null);

  protected readonly run = rxResource({
    params: () => this.selectedId() ?? undefined,
    stream: ({ params }) => (params ? this.payments.reconciliation(params) : of(undefined)),
  });

  protected readonly clean = computed(() => {
    const r = this.run.value();
    return !!r && !r.missingCount && !r.extraCount && !r.uncreditedCount;
  });

  protected readonly columns: ColumnDef<ReconciliationRun>[] = [
    { key: 'createdAt', header: 'Uploaded', value: (r) => formatDateTime(r.createdAt) },
    {
      key: 'period',
      header: 'Statement covers',
      value: (r) => `${formatDateTime(r.periodFrom)} – ${formatDateTime(r.periodTo)}`,
    },
    { key: 'rows', header: 'Rows', value: (r) => r.statementRows, align: 'end' },
    { key: 'missing', header: 'Missing here', value: (r) => r.missingCount, align: 'end' },
    { key: 'extra', header: 'Not on statement', value: (r) => r.extraCount, align: 'end' },
    { key: 'uncredited', header: 'Not credited', value: (r) => r.uncreditedCount, align: 'end' },
  ];

  protected async upload(input: HTMLInputElement): Promise<void> {
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    if (file.size > MAX_STATEMENT_BYTES) {
      this.snack.open('That file is too large for an M-Pesa statement export.', 'Dismiss', {
        duration: 6000,
      });
      return;
    }
    this.uploading.set(true);
    const csv = await file.text();
    this.payments.reconcile(csv).subscribe({
      next: (run) => {
        this.uploading.set(false);
        this.version.update((v) => v + 1);
        this.selectedId.set(run.id ?? null);
      },
      error: (e: unknown) => {
        this.uploading.set(false);
        this.snack.open(errorMessage(e, 'The statement could not be read.'), 'Dismiss', {
          duration: 8000,
        });
      },
    });
  }

  protected investigate(receipt: string): void {
    this.payments.investigate(receipt).subscribe({
      next: () => {
        this.asked.update((set) => new Set([...set, receipt]));
        this.snack.open(
          `Asked M-Pesa about ${receipt}. If it completed, it arrives as a payment.`,
          undefined,
          {
            duration: 5000,
          },
        );
      },
      error: (e: unknown) => this.snack.open(errorMessage(e), 'Dismiss', { duration: 8000 }),
    });
  }
}
