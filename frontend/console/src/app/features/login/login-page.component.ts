import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, inject, input, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router } from '@angular/router';

import { AuthService } from '../../core/auth/auth.service';
import { SignInResponse } from '../../shared/models';

export type Step =
  'credentials' | 'CHANGE_PASSWORD' | 'TOTP_REQUIRED' | 'TOTP_ENROLL' | 'SIGNED_IN';

/** What the server asked for next; an answer the console does not know starts over. */
export function nextStep(response: SignInResponse): Step {
  switch (response.status) {
    case 'SIGNED_IN':
    case 'CHANGE_PASSWORD':
    case 'TOTP_REQUIRED':
    case 'TOTP_ENROLL':
      return response.status;
    default:
      return 'credentials';
  }
}

/** Only a path inside this app, never another site (an open redirect), and never the sign-in page. */
export function safeNext(next: string | undefined): string {
  return next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/login')
    ? next
    : '/tenants';
}

/**
 * Platform admin sign-in (ADR 0010): email and password, then whatever the server asks next: a new
 * password (a bootstrap or break-glass password is temporary), the authenticator code or a recovery
 * code, or setting up the authenticator, whose recovery codes are shown only then. TOTP is mandatory
 * for every platform admin; the page never decides who may sign in.
 */
@Component({
  selector: 'app-login-page',
  imports: [
    NgTemplateOutlet,
    ReactiveFormsModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
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

  protected readonly step = signal<Step>('credentials');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly enrolment = signal<SignInResponse | null>(null);
  private challenge = '';

  protected readonly credentials = this.fb.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', Validators.required],
  });
  protected readonly newPassword = this.fb.group({
    password: ['', [Validators.required, Validators.minLength(12)]],
  });
  protected readonly code = this.fb.group({
    code: ['', [Validators.required, Validators.pattern(/^\s*[0-9A-Za-z-]{6,12}\s*$/)]],
  });

  protected signIn(): void {
    const { email, password } = this.credentials.getRawValue();
    void this.run(() => this.auth.login(email.trim(), password));
  }

  protected setPassword(): void {
    void this.run(() =>
      this.auth.changePassword(this.challenge, this.newPassword.getRawValue().password),
    );
  }

  protected verify(): void {
    const code = this.code.getRawValue().code.trim();
    void this.run(() =>
      this.step() === 'TOTP_ENROLL'
        ? this.auth.enrollTotp(this.challenge, code)
        : this.auth.verifyTotp(this.challenge, code),
    );
  }

  protected restart(): void {
    this.step.set('credentials');
    this.error.set(null);
    this.enrolment.set(null);
    this.credentials.controls.password.reset();
  }

  private async run(call: () => Promise<SignInResponse>): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      const response = await call();
      const step = nextStep(response);
      if (step === 'SIGNED_IN') {
        await this.router.navigateByUrl(safeNext(this.next()));
        return;
      }
      this.challenge = response.challenge ?? '';
      this.enrolment.set(step === 'TOTP_ENROLL' ? response : null);
      this.code.reset();
      this.step.set(step);
    } catch (e) {
      this.error.set(message(e));
    } finally {
      this.busy.set(false);
    }
  }
}

function message(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    const detail = (e.error as { detail?: string } | null)?.detail;
    if (e.status === 0) {
      return 'The server cannot be reached. Check your connection and try again.';
    }
    if (detail) {
      return detail;
    }
  }
  return 'Sign-in failed. Try again.';
}
