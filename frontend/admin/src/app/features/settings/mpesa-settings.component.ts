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
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { errorMessage } from '../../core/api/requests';
import { CopyButtonComponent } from '../../shared/ui/copy-button.component';
import { formatDateTime } from '../../shared/ui/format';
import { MpesaRequest, SettingsService } from './data/settings.service';

/** Credentials and the secret kind that records when each was set. */
const CREDENTIALS = [
  {
    control: 'consumerKey',
    kind: 'MPESA_CONSUMER_KEY',
    label: 'Consumer key',
    hint: 'From the Daraja app',
  },
  {
    control: 'consumerSecret',
    kind: 'MPESA_CONSUMER_SECRET',
    label: 'Consumer secret',
    hint: 'From the Daraja app',
  },
  {
    control: 'passkey',
    kind: 'MPESA_PASSKEY',
    label: 'Lipa na M-Pesa passkey',
    hint: 'Needed to send payment requests (STK Push)',
  },
  {
    control: 'initiatorName',
    kind: 'MPESA_INITIATOR_NAME',
    label: 'Initiator name',
    hint: 'Needed to ask M-Pesa about a missing payment',
  },
  {
    control: 'securityCredential',
    kind: 'MPESA_SECURITY_CREDENTIAL',
    label: 'Security credential',
    hint: "The initiator's encrypted password from the Daraja portal",
  },
] as const;

const URL_LABELS: Record<string, string> = {
  c2bConfirmation: 'C2B confirmation',
  c2bValidation: 'C2B validation',
  stk: 'STK Push result',
  transactionStatusResult: 'Transaction Status result',
  transactionStatusTimeout: 'Transaction Status timeout',
};

/**
 * The tenant's Paybill or Till and its Daraja credentials (settings.secrets). Credentials are
 * write-only (T4): the form shows only when each was last set, and a blank field keeps it.
 */
@Component({
  selector: 'app-mpesa-settings',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    CopyButtonComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './mpesa-settings.component.html',
  styleUrl: './settings-section.scss',
})
export class MpesaSettingsComponent {
  private readonly settings = inject(SettingsService);
  private readonly snack = inject(MatSnackBar);

  protected readonly credentials = CREDENTIALS;
  protected readonly date = formatDateTime;
  protected readonly busy = signal(false);
  protected readonly registering = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly version = signal(0);

  protected readonly current = rxResource({
    params: () => this.version(),
    stream: () => this.settings.mpesa(),
  });
  protected readonly secrets = rxResource({
    params: () => this.version(),
    stream: () => this.settings.secrets(),
  });
  protected readonly setAt = computed(
    () => new Map((this.secrets.value() ?? []).map((s) => [s.kind ?? '', s.updatedAt ?? ''])),
  );
  /** In the order staff set them up: C2B first, then STK, then Transaction Status. */
  protected readonly urls = computed(() => {
    const urls = this.current.value()?.callbackUrls ?? {};
    const order = Object.keys(URL_LABELS);
    return Object.entries(urls)
      .sort(([a], [b]) => rank(order, a) - rank(order, b))
      .map(([key, url]) => ({ label: URL_LABELS[key] ?? key, url }));
  });

  protected readonly form = inject(NonNullableFormBuilder).group({
    shortcode: ['', [Validators.required, Validators.pattern(/^[0-9]{5,7}$/)]],
    shortcodeType: ['PAYBILL' as 'PAYBILL' | 'TILL', Validators.required],
    sandbox: [false],
    validationEnabled: [false],
    consumerKey: [''],
    consumerSecret: [''],
    passkey: [''],
    initiatorName: [''],
    securityCredential: [''],
  });

  constructor() {
    // Load the saved account into the form once it arrives; credentials always start blank.
    effect(() => {
      const current = this.current.value();
      if (current) {
        this.form.patchValue({
          shortcode: current.shortcode ?? '',
          shortcodeType: (current.shortcodeType as 'PAYBILL' | 'TILL') ?? 'PAYBILL',
          sandbox: !!current.sandbox,
          validationEnabled: !!current.validationEnabled,
        });
      }
    });
  }

  protected save(): void {
    const value = this.form.getRawValue();
    const blankAsNull = (v: string) => v.trim() || undefined;
    const request: MpesaRequest = {
      shortcode: value.shortcode.trim(),
      shortcodeType: value.shortcodeType,
      sandbox: value.sandbox,
      validationEnabled: value.validationEnabled,
      consumerKey: blankAsNull(value.consumerKey),
      consumerSecret: blankAsNull(value.consumerSecret),
      passkey: blankAsNull(value.passkey),
      initiatorName: blankAsNull(value.initiatorName),
      securityCredential: blankAsNull(value.securityCredential),
    };
    this.busy.set(true);
    this.error.set(null);
    this.settings.saveMpesa(request).subscribe({
      next: () => {
        this.busy.set(false);
        this.form.patchValue({
          consumerKey: '',
          consumerSecret: '',
          passkey: '',
          initiatorName: '',
          securityCredential: '',
        });
        this.form.markAsPristine();
        this.snack.open('M-Pesa settings saved.', undefined, { duration: 4000 });
        this.version.update((v) => v + 1);
      },
      error: (e: unknown) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  protected registerUrls(): void {
    this.registering.set(true);
    this.settings.registerUrls().subscribe({
      next: () => {
        this.registering.set(false);
        this.snack.open('Safaricom accepted the callback URLs.', undefined, { duration: 4000 });
        this.version.update((v) => v + 1);
      },
      error: (e: unknown) => {
        this.registering.set(false);
        this.snack.open(errorMessage(e, 'Safaricom did not accept the URLs.'), 'Dismiss', {
          duration: 8000,
        });
      },
    });
  }
}

function rank(order: string[], key: string): number {
  const i = order.indexOf(key);
  return i < 0 ? order.length : i;
}
