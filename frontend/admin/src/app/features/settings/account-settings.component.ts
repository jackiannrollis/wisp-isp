import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { errorMessage } from '../../core/api/requests';
import { TenantThemeService } from '../../core/theme/tenant-theme.service';
import { formatDateTime } from '../../shared/ui/format';
import { AccountSettingsRequest, SettingsService } from './data/settings.service';

const HEX = /^#[0-9a-fA-F]{6}$/;
const HTTPS = /^\s*(https:\/\/\S+)?\s*$/;

/**
 * The account's own policy and look (settings.write, Owner only; decisions.md 2026-10-21). Grace and
 * the partial-payment rule apply from each line's next renewal (S6), the pause limit to the next
 * pause, branding at once: the theme follows as soon as it is saved.
 */
@Component({
  selector: 'app-account-settings',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatSnackBarModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (current.isLoading() && !current.value()) {
      <p class="muted">Loading…</p>
    } @else {
      <form class="layout" [formGroup]="form" (ngSubmit)="save()">
        <section class="panel form">
          <h2 class="section-title">Branding</h2>
          <p class="help">Shown on the admin app, the customer portal and the pay page.</p>
          <mat-form-field appearance="outline">
            <mat-label>Account name</mat-label>
            <input matInput formControlName="name" maxlength="100" />
          </mat-form-field>
          <div class="colour">
            <input
              type="color"
              aria-label="Pick the brand colour"
              [value]="colour()"
              (input)="pickColour($event)"
            />
            <mat-form-field appearance="outline">
              <mat-label>Brand colour</mat-label>
              <input matInput formControlName="brandColor" maxlength="7" />
              <mat-hint>Buttons, links and the active menu item</mat-hint>
            </mat-form-field>
          </div>
          <mat-form-field appearance="outline">
            <mat-label>Logo address</mat-label>
            <input matInput formControlName="logoUrl" placeholder="https://…" />
            <mat-hint>An https link to a square image; blank shows your initial</mat-hint>
          </mat-form-field>
          <dl class="fixed">
            <div>
              <dt>Account code</dt>
              <dd>{{ current.value()?.code }}</dd>
            </div>
            <div>
              <dt>Time zone</dt>
              <dd>{{ current.value()?.timeZone }}</dd>
            </div>
            <div>
              <dt>Currency</dt>
              <dd>{{ current.value()?.currency }}</dd>
            </div>
            <div>
              <dt>Cutover window</dt>
              <dd>
                @if (current.value()?.cutoverWindowUntil; as until) {
                  open until {{ date(until) }}
                } @else {
                  closed
                }
              </dd>
            </div>
          </dl>
          <p class="help">
            The code prefixes every customer number; these three are fixed. Corebalt opens the
            cutover window when you move existing customers over with the expiry they already paid
            for.
          </p>
        </section>

        <section class="panel form">
          <h2 class="section-title">Billing policy</h2>
          <p class="help">
            Changes apply from each line's next renewal. A cycle a customer already paid for keeps
            the grace it was sold with.
          </p>
          <mat-form-field appearance="outline">
            <mat-label>Default grace days</mat-label>
            <input matInput type="number" min="0" max="30" formControlName="defaultGraceDays" />
            <mat-hint
              >Days online after expiry before restriction; a package can override it</mat-hint
            >
          </mat-form-field>
          <span class="label">During grace</span>
          <mat-button-toggle-group formControlName="graceMode" aria-label="During grace">
            <mat-button-toggle value="FULL_SPEED">Full speed</mat-button-toggle>
            <mat-button-toggle value="THROTTLED">Throttle speeds</mat-button-toggle>
          </mat-button-toggle-group>
          <span class="label">A payment below the price</span>
          <mat-button-toggle-group
            formControlName="partialPaymentRule"
            aria-label="Partial payments"
          >
            <mat-button-toggle value="WAIT_FOR_FULL">Waits in the wallet</mat-button-toggle>
            <mat-button-toggle value="PRO_RATA_DAYS">Buys whole days</mat-button-toggle>
          </mat-button-toggle-group>
          <mat-form-field appearance="outline" class="pause">
            <mat-label>Longest pause</mat-label>
            <input matInput type="number" min="1" max="365" formControlName="maxPauseDays" />
            <span matTextSuffix>days</span>
            <mat-hint>Applies to the next pause a customer asks for</mat-hint>
          </mat-form-field>
          @if (error(); as error) {
            <p class="error" role="alert">{{ error }}</p>
          }
          <div class="actions">
            <button
              mat-flat-button
              type="submit"
              [disabled]="form.invalid || busy() || form.pristine"
            >
              Save
            </button>
          </div>
        </section>
      </form>
    }
  `,
  styleUrl: './settings-section.scss',
  styles: `
    .colour {
      display: flex;
      align-items: flex-start;
      gap: 0.75rem;
    }
    .colour input[type='color'] {
      width: 3rem;
      height: 3.5rem;
      padding: 0;
      border: var(--app-border);
      border-radius: var(--app-radius-s);
      background: none;
      cursor: pointer;
    }
    .colour mat-form-field {
      flex: 1;
    }
    .fixed {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 0.5rem;
      margin: 0.5rem 0 0;
    }
    .fixed dt,
    .label {
      font: var(--mat-sys-label-medium);
      color: var(--mat-sys-on-surface-variant);
    }
    .fixed dd {
      margin: 0;
      font-weight: 600;
    }
    .label {
      margin-top: 0.5rem;
    }
    mat-button-toggle-group {
      align-self: flex-start;
    }
    .pause {
      margin-top: 1rem;
    }
  `,
})
export class AccountSettingsComponent {
  private readonly settings = inject(SettingsService);
  private readonly snack = inject(MatSnackBar);
  private readonly theme = inject(TenantThemeService);

  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly date = formatDateTime;
  protected readonly current = rxResource({ stream: () => this.settings.account() });

  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    brandColor: ['#0f766e', [Validators.required, Validators.pattern(HEX)]],
    logoUrl: ['', [Validators.pattern(HTTPS), Validators.maxLength(500)]],
    defaultGraceDays: [0, [Validators.required, Validators.min(0), Validators.max(30)]],
    graceMode: ['FULL_SPEED' as 'FULL_SPEED' | 'THROTTLED'],
    partialPaymentRule: ['WAIT_FOR_FULL' as 'WAIT_FOR_FULL' | 'PRO_RATA_DAYS'],
    maxPauseDays: [30, [Validators.required, Validators.min(1), Validators.max(365)]],
  });

  private readonly values = toSignal(this.form.valueChanges, { initialValue: this.form.value });
  /** The colour picker follows the text field while it holds a valid colour. */
  protected readonly colour = computed(() => {
    const value = this.values().brandColor ?? '';
    return HEX.test(value) ? value : '#0f766e';
  });

  constructor() {
    effect(() => {
      const c = this.current.value();
      if (c) {
        this.form.patchValue({
          name: c.name ?? '',
          brandColor: c.brandColor ?? '#0f766e',
          logoUrl: c.logoUrl ?? '',
          defaultGraceDays: c.defaultGraceDays ?? 0,
          graceMode: (c.graceMode as 'FULL_SPEED' | 'THROTTLED') ?? 'FULL_SPEED',
          partialPaymentRule:
            (c.partialPaymentRule as 'WAIT_FOR_FULL' | 'PRO_RATA_DAYS') ?? 'WAIT_FOR_FULL',
          maxPauseDays: c.maxPauseDays ?? 30,
        });
        this.form.markAsPristine();
      }
    });
  }

  protected pickColour(event: Event): void {
    this.form.controls.brandColor.setValue((event.target as HTMLInputElement).value);
    this.form.controls.brandColor.markAsDirty();
  }

  protected save(): void {
    const v = this.form.getRawValue();
    const request: AccountSettingsRequest = {
      name: v.name.trim(),
      brandColor: v.brandColor,
      logoUrl: v.logoUrl.trim() || undefined,
      defaultGraceDays: v.defaultGraceDays,
      graceMode: v.graceMode,
      partialPaymentRule: v.partialPaymentRule,
      maxPauseDays: v.maxPauseDays,
    };
    this.busy.set(true);
    this.error.set(null);
    this.settings.saveAccount(request).subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.form.markAsPristine();
        // Branding applies at once: repaint the app with the saved colour, name and logo.
        this.theme.apply({
          name: saved.name,
          logoUrl: saved.logoUrl ?? null,
          primaryColor: saved.brandColor,
        });
        this.snack.open('Account settings saved.', undefined, { duration: 4000 });
      },
      error: (e: unknown) => {
        this.busy.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }
}
