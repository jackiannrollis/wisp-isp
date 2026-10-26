import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import {
  IconDownload,
  IconFileUpload,
  IconMessage,
  TablerIconComponent,
} from '@tabler/icons-angular';

import { errorMessage } from '../../core/api/requests';
import { PermissionService } from '../../core/permissions/permission.service';
import { formatDateTime, formatMoney } from '../../shared/ui/format';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { RoutersService } from '../network/data/routers.service';
import {
  CustomerImportsService,
  ImportOptions,
  ImportPreview,
  ImportResult,
  ImportResultRow,
  MovedNotices,
} from './data/customer-imports.service';

/** A customer list larger than this is not one CSV export of an ISP's customers. */
const MAX_FILE_BYTES = 5 * 1024 * 1024;

export const IMPORT_COLUMNS =
  'name, phone, email, address, installation_location, lat, lng, package, paid_until, pppoe_username, pppoe_password, legacy_account_ref';

/**
 * The result as a CSV for the password manager: one line per row, passwords included, so it is
 * saved once and deleted once every customer router is configured (ADR 0009).
 */
export function resultCsv(rows: readonly ImportResultRow[]): string {
  const cell = (value: unknown): string => {
    const text = value == null ? '' : String(value);
    return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  };
  const header = 'row,number,name,pppoe_username,pppoe_password,credential_kept,activated_until';
  const lines = rows.map((r) =>
    [
      r.row,
      r.number,
      r.name,
      r.pppoeUsername,
      r.pppoePassword,
      r.credentialKept ? 'yes' : 'no',
      r.activatedUntil,
    ]
      .map(cell)
      .join(','),
  );
  return [header, ...lines].join('\r\n') + '\r\n';
}

/**
 * Import customers from CSV (docs/07 step 4; the pilot-onboarding runbook): a preview of what the
 * file does, then the import, then the one-time answer with generated passwords, and the "we've
 * moved" SMS to the batch (ADR 0014).
 */
@Component({
  selector: 'app-customer-import-page',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatSnackBarModule,
    TablerIconComponent,
    PageHeaderComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './import-page.component.html',
  styleUrl: './import-page.component.scss',
})
export class CustomerImportPageComponent {
  private readonly imports = inject(CustomerImportsService);
  private readonly routersService = inject(RoutersService);
  private readonly permissions = inject(PermissionService);
  private readonly snack = inject(MatSnackBar);

  protected readonly uploadIcon = IconFileUpload;
  protected readonly downloadIcon = IconDownload;
  protected readonly messageIcon = IconMessage;
  protected readonly columns = IMPORT_COLUMNS;
  protected readonly money = formatMoney;
  protected readonly date = formatDateTime;

  protected readonly routers = toSignal(this.routersService.all(), { initialValue: [] });
  protected readonly form = inject(NonNullableFormBuilder).group({
    routerId: [''],
    timeOfDay: ['12:00'],
  });

  protected readonly fileName = signal<string | null>(null);
  protected readonly csv = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly preview = signal<ImportPreview | null>(null);
  protected readonly result = signal<ImportResult | null>(null);
  protected readonly notices = signal<MovedNotices | null>(null);

  protected readonly canImport = computed(() => {
    const p = this.permissions.permissions();
    return p.has('customers.write') && p.has('subscriptions.write');
  });
  protected readonly canActivate = computed(() =>
    this.permissions.permissions().has('subscriptions.import_active'),
  );
  protected readonly activates = computed(() => (this.preview()?.activation?.total ?? 0) > 0);

  constructor() {
    // The confirmation covers the file and the options: a change needs a new preview.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.preview.set(null));
  }

  protected async choose(input: HTMLInputElement): Promise<void> {
    const file = input.files?.[0];
    input.value = '';
    if (!file) {
      return;
    }
    if (file.size > MAX_FILE_BYTES) {
      this.snack.open('That file is too large for a customer list.', 'Dismiss', { duration: 6000 });
      return;
    }
    this.csv.set(await file.text());
    this.fileName.set(file.name);
    this.preview.set(null);
    this.result.set(null);
    this.notices.set(null);
    this.error.set(null);
  }

  protected runPreview(): void {
    const csv = this.csv();
    if (!csv) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.imports.preview(csv, this.options()).subscribe({
      next: (preview) => {
        this.preview.set(preview);
        this.busy.set(false);
      },
      error: (e: unknown) => this.fail(e, 'The file could not be checked.'),
    });
  }

  protected commit(): void {
    const csv = this.csv();
    const preview = this.preview();
    if (!csv || !preview) {
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    this.imports.import(csv, this.options(), preview.confirmation).subscribe({
      next: (result) => {
        this.result.set(result);
        this.preview.set(null);
        this.csv.set(null);
        this.busy.set(false);
      },
      error: (e: unknown) => this.fail(e, 'The import did not go through; nothing was imported.'),
    });
  }

  protected download(): void {
    const result = this.result();
    if (!result?.rows) {
      return;
    }
    const blob = new Blob([resultCsv(result.rows)], { type: 'text/csv' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = `import-${result.batchId}.csv`;
    anchor.click();
    URL.revokeObjectURL(url);
  }

  protected sendNotices(): void {
    const result = this.result();
    if (!result?.batchId) {
      return;
    }
    this.busy.set(true);
    this.imports.sendMovedNotices(result.batchId).subscribe({
      next: (notices) => {
        this.notices.set(notices);
        this.busy.set(false);
        this.snack.open(
          `Queued ${notices.queued} of ${notices.customers} messages for the sending window.`,
          undefined,
          { duration: 5000 },
        );
      },
      error: (e: unknown) => this.fail(e, 'The messages could not be queued.'),
    });
  }

  private options(): ImportOptions {
    const value = this.form.getRawValue();
    return {
      routerId: value.routerId || undefined,
      timeOfDay: value.timeOfDay || undefined,
    };
  }

  private fail(e: unknown, fallback: string): void {
    this.busy.set(false);
    this.error.set(errorMessage(e, fallback));
  }
}
