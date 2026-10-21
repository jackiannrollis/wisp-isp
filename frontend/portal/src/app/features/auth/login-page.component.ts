import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router } from '@angular/router';

import { errorMessage } from '../../core/api/requests';
import { AuthService, tenantOfAccount } from '../../core/auth/auth.service';
import { DEFAULT_BRANDING } from '../../core/theme/tenant-theme.service';
import { SignInAccount, SignInResponse } from '../../shared/models';

type Step = 'pin' | 'phone' | 'code' | 'CHOOSE_ACCOUNT' | 'PIN_SETUP';

const ACCOUNT = /^\s*[A-Za-z]{2,4}\d{6}(-\d+)?\s*$/;
const PIN = /^\d{4,6}$/;
const PROVIDER = 'corebalt.portal.provider';

/**
 * Customer sign-in (ADR 0010, Q7 default): account number and PIN as the quick path; a code by SMS
 * to set the PIN the first time or to reset it, then the account to set it for when one phone holds
 * several. The PIN rules (not all the same digit, not a run) and the lockout are the server's.
 */
@Component({
  selector: 'app-login-page',
  imports: [
    ReactiveFormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './login-page.component.html',
  styleUrl: './login-page.component.scss',
})
export class LoginPageComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly fb = inject(NonNullableFormBuilder);

  /** Where to go after signing in (the guard passes the page that asked). */
  readonly next = input<string>();

  protected readonly brandName = DEFAULT_BRANDING.name;
  protected readonly step = signal<Step>('pin');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly accounts = signal<SignInAccount[]>([]);
  protected readonly chosen = signal<SignInAccount | null>(null);
  private challenge = '';

  protected readonly pinForm = this.fb.group({
    account: ['', [Validators.required, Validators.pattern(ACCOUNT)]],
    pin: ['', [Validators.required, Validators.pattern(PIN)]],
  });
  protected readonly phoneForm = this.fb.group({
    tenant: [remembered(), [Validators.required, Validators.pattern(/^\s*[A-Za-z0-9-]{2,32}\s*$/)]],
    phone: ['', [Validators.required, Validators.pattern(/^\s*\+?[\d\s]{9,15}\s*$/)]],
  });
  protected readonly codeForm = this.fb.group({
    code: ['', [Validators.required, Validators.pattern(/^\s*\d{6}\s*$/)]],
  });
  protected readonly newPinForm = this.fb.group({
    pin: ['', [Validators.required, Validators.pattern(PIN)]],
    repeat: ['', Validators.required],
  });

  protected signIn(): void {
    const { account, pin } = this.pinForm.getRawValue();
    const tenant = tenantOfAccount(account) ?? '';
    remember(tenant);
    void this.run(() => this.auth.login(tenant, account.trim().toUpperCase(), pin));
  }

  protected useCode(): void {
    const account = tenantOfAccount(this.pinForm.getRawValue().account);
    if (account && !this.phoneForm.controls.tenant.value) {
      this.phoneForm.controls.tenant.setValue(account);
    }
    this.go('phone');
  }

  protected async sendCode(): Promise<void> {
    const { tenant, phone } = this.phoneForm.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.auth.requestCode(tenant.trim(), phone.trim());
      remember(tenant.trim().toUpperCase());
      this.codeForm.reset();
      this.step.set('code');
    } catch (e) {
      this.error.set(errorMessage(e, 'The code could not be sent. Try again.'));
    } finally {
      this.busy.set(false);
    }
  }

  protected verifyCode(): void {
    const { tenant, phone } = this.phoneForm.getRawValue();
    const code = this.codeForm.getRawValue().code.trim();
    void this.run(() => this.auth.verifyCode(tenant.trim(), phone.trim(), code));
  }

  protected choose(account: SignInAccount): void {
    this.chosen.set(account);
    this.challenge = account.challenge;
    this.go('PIN_SETUP');
  }

  protected setPin(): void {
    const { pin, repeat } = this.newPinForm.getRawValue();
    if (pin !== repeat) {
      this.error.set('The two PINs are not the same.');
      return;
    }
    void this.run(() => this.auth.setPin(this.challenge, pin));
  }

  protected restart(): void {
    this.pinForm.controls.pin.reset();
    this.newPinForm.reset();
    this.chosen.set(null);
    this.go('pin');
  }

  protected go(step: Step): void {
    this.error.set(null);
    this.step.set(step);
  }

  private async run(call: () => Promise<SignInResponse>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const response = await call();
      if (response.status === 'SIGNED_IN') {
        await this.router.navigateByUrl(this.safeNext());
        return;
      }
      if (response.status === 'CHOOSE_ACCOUNT') {
        this.accounts.set(response.accounts ?? []);
      } else {
        this.challenge = response.challenge ?? '';
      }
      this.newPinForm.reset();
      this.step.set(response.status);
    } catch (e) {
      this.error.set(errorMessage(e, 'Sign-in failed. Try again.'));
    } finally {
      this.busy.set(false);
    }
  }

  /** Only a path inside this app, never another site (an open redirect). */
  private safeNext(): string {
    const next = this.next();
    return next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/login')
      ? next
      : '/status';
  }
}

/** The provider code typed last on this device, a convenience only (never a secret). */
function remembered(): string {
  try {
    return localStorage.getItem(PROVIDER) ?? '';
  } catch {
    return '';
  }
}

function remember(tenant: string): void {
  try {
    if (tenant) {
      localStorage.setItem(PROVIDER, tenant);
    }
  } catch {
    // Storage blocked: the field simply starts empty next time.
  }
}
