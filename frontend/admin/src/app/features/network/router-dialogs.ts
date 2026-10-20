import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

import { errorMessage } from '../../core/api/requests';
import { Router, RouterWithSecret, RoutersService } from './data/routers.service';

const STYLES = `
  form { display: flex; flex-direction: column; gap: 0.25rem; min-width: min(26rem, 80vw); padding-top: 0.5rem; }
  .error { color: var(--mat-sys-error); margin: 0; }
  .hint { color: var(--mat-sys-on-surface-variant); margin: 0 0 0.75rem; }
`;

const IPV4 = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;

/** Register a router by its WireGuard tunnel address; its RADIUS secret is then shown once (T4). */
@Component({
  selector: 'app-register-router-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>Register a router</h2>
    <mat-dialog-content>
      <p class="hint">
        Use the address the router has on the WireGuard tunnel to hub A. RADIUS and monitoring reach
        it only there (N6).
      </p>
      <form [formGroup]="form" id="router-form" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Name</mat-label>
          <input matInput formControlName="name" maxlength="100" placeholder="ruiru-core-1" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Tunnel address</mat-label>
          <input
            matInput
            formControlName="tunnelIp"
            placeholder="10.200.0.21"
            inputmode="decimal"
          />
          <mat-hint>IPv4 on the WireGuard tunnel</mat-hint>
        </mat-form-field>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="router-form" [disabled]="form.invalid || busy()">
        Register
      </button>
    </mat-dialog-actions>
  `,
})
export class RegisterRouterDialogComponent {
  private readonly routers = inject(RoutersService);
  private readonly ref =
    inject<MatDialogRef<RegisterRouterDialogComponent, RouterWithSecret>>(MatDialogRef);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    name: ['', [Validators.required, Validators.maxLength(100)]],
    tunnelIp: ['', [Validators.required, Validators.pattern(IPV4)]],
  });

  protected save(): void {
    const { name, tunnelIp } = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    this.routers.register(name.trim(), tunnelIp.trim()).subscribe({
      next: (created) => this.ref.close(created),
      error: (e: unknown) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}

/** The RouterOS REST login the tunnel worker polls with; stored encrypted, never shown again (T4). */
@Component({
  selector: 'app-rest-credential-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: STYLES,
  template: `
    <h2 mat-dialog-title>Monitoring login for {{ data.router.name }}</h2>
    <mat-dialog-content>
      <p class="hint">
        The read-only REST user the onboarding script created on the router. The password is stored
        encrypted and can only be replaced, never read back.
      </p>
      <form [formGroup]="form" id="rest-form" (ngSubmit)="save()">
        <mat-form-field appearance="outline">
          <mat-label>Username</mat-label>
          <input matInput formControlName="username" autocomplete="off" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Password</mat-label>
          <input matInput type="password" formControlName="password" autocomplete="new-password" />
        </mat-form-field>
        @if (error(); as error) {
          <p class="error" role="alert">{{ error }}</p>
        }
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button mat-flat-button type="submit" form="rest-form" [disabled]="form.invalid || busy()">
        Save
      </button>
    </mat-dialog-actions>
  `,
})
export class RestCredentialDialogComponent {
  protected readonly data = inject<{ router: Router }>(MAT_DIALOG_DATA);
  private readonly routers = inject(RoutersService);
  private readonly ref = inject<MatDialogRef<RestCredentialDialogComponent, Router>>(MatDialogRef);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    username: [this.data.router.restUsername ?? 'corebalt-api', Validators.required],
    password: ['', Validators.required],
  });

  protected save(): void {
    const { username, password } = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    this.routers.setRestCredential(this.data.router.id ?? '', username.trim(), password).subscribe({
      next: (router) => this.ref.close(router),
      error: (e: unknown) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }
}
