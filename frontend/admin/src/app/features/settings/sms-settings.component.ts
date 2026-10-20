import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { of } from 'rxjs';

import { errorMessage } from '../../core/api/requests';
import { PermissionService } from '../../core/permissions/permission.service';
import { CopyButtonComponent } from '../../shared/ui/copy-button.component';
import { formatDateTime } from '../../shared/ui/format';
import { SettingsService, SmsRequest } from './data/settings.service';

const TIME = /^([01][0-9]|2[0-3]):[0-5][0-9]$/;
const SENDER_ID = /^[A-Za-z0-9 _-]{1,11}$/;

/**
 * The tenant's Africa's Talking account and the reminder sending window (M2). Saving needs
 * settings.secrets because the API key travels with it; the key is write-only (T4).
 */
@Component({
  selector: 'app-sms-settings',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    CopyButtonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './sms-settings.component.html',
  styleUrl: './settings-section.scss',
})
export class SmsSettingsComponent {
  private readonly settings = inject(SettingsService);
  private readonly snack = inject(MatSnackBar);
  private readonly permissions = inject(PermissionService);

  protected readonly date = formatDateTime;
  protected readonly canEdit = computed(() =>
    this.permissions.permissions().has('settings.secrets'),
  );
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly version = signal(0);

  protected readonly current = rxResource({
    params: () => this.version(),
    stream: () => this.settings.sms(),
  });
  /** When the API key was last set; only staff who may set secrets can see that. */
  protected readonly keySetAt = rxResource({
    params: () => ({ version: this.version(), canEdit: this.canEdit() }),
    stream: ({ params }) => (params.canEdit ? this.settings.secrets() : of([])),
  });
  protected readonly apiKeySetAt = computed(
    () => (this.keySetAt.value() ?? []).find((s) => s.kind === 'SMS_API_KEY')?.updatedAt ?? null,
  );

  protected readonly form = inject(NonNullableFormBuilder).group({
    atUsername: ['', [Validators.required, Validators.maxLength(100)]],
    senderId: ['', [Validators.required, Validators.pattern(SENDER_ID)]],
    sandbox: [false],
    enabled: [true],
    windowStart: ['07:00', [Validators.required, Validators.pattern(TIME)]],
    windowEnd: ['21:00', [Validators.required, Validators.pattern(TIME)]],
    apiKey: [''],
  });

  constructor() {
    effect(() => {
      const current = this.current.value();
      if (current) {
        this.form.patchValue({
          atUsername: current.atUsername ?? '',
          senderId: current.senderId ?? '',
          sandbox: !!current.sandbox,
          enabled: current.enabled ?? true,
          windowStart: current.windowStart ?? '07:00',
          windowEnd: current.windowEnd ?? '21:00',
        });
        this.form.markAsPristine();
      }
    });
    effect(() => (this.canEdit() ? this.form.enable() : this.form.disable()));
  }

  protected save(): void {
    const v = this.form.getRawValue();
    if (v.windowStart >= v.windowEnd) {
      this.error.set('The sending window must start before it ends.');
      return;
    }
    const request: SmsRequest = {
      atUsername: v.atUsername.trim(),
      senderId: v.senderId.trim(),
      sandbox: v.sandbox,
      enabled: v.enabled,
      windowStart: v.windowStart,
      windowEnd: v.windowEnd,
      apiKey: v.apiKey.trim() || undefined,
    };
    this.busy.set(true);
    this.error.set(null);
    this.settings.saveSms(request).subscribe({
      next: () => {
        this.busy.set(false);
        this.form.patchValue({ apiKey: '' });
        this.snack.open('SMS settings saved.', undefined, { duration: 4000 });
        this.version.update((n) => n + 1);
      },
      error: (e: unknown) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }
}
