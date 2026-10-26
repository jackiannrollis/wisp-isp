import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

import { errorMessage } from '../../core/api/requests';
import {
  Router,
  RouterOnboardingRequest,
  RouterOnboardingScript,
  RouterWithSecret,
  RoutersService,
} from './data/routers.service';

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

const RANGE_OR_CIDR = /^\d{1,3}(\.\d{1,3}){3}(-\d{1,3}(\.\d{1,3}){3}|\/\d{1,2})$/;
const CIDR_LIST = /^\d{1,3}(\.\d{1,3}){3}(\/\d{1,2})?(,\d{1,3}(\.\d{1,3}){3}(\/\d{1,2})?)*$/;

/**
 * The router's onboarding script (docs/06, ADR 0013): the ISP's addressing on the router in, the
 * script out, shown once. Generating it rotates the router's WireGuard key, RADIUS secret and REST
 * login, so the warning stays on screen until the script is taken.
 */
@Component({
  selector: 'app-onboarding-script-dialog',
  imports: [
    ReactiveFormsModule,
    MatDialogModule,
    MatButtonModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: [
    STYLES,
    `
      .script {
        width: 100%;
        min-height: 18rem;
        font-family: ui-monospace, monospace;
        font-size: 0.8rem;
        white-space: pre;
      }
      .warn {
        color: var(--mat-sys-error);
        margin: 0 0 0.75rem;
      }
      .actions-row {
        display: flex;
        gap: 0.5rem;
        flex-wrap: wrap;
        margin-bottom: 0.75rem;
      }
    `,
  ],
  template: `
    <h2 mat-dialog-title>Onboarding script for {{ data.router.name }}</h2>
    <mat-dialog-content>
      @if (result(); as r) {
        <p class="warn">
          Shown only now. It holds the router's new WireGuard private key, RADIUS secret, REST
          password and SNMP community: save it, paste it into the router's terminal, then delete the
          file.
        </p>
        <div class="actions-row">
          <button mat-stroked-button type="button" (click)="copy(r.script ?? '')">
            {{ copied() ? 'Copied' : 'Copy' }}
          </button>
          <button mat-stroked-button type="button" (click)="download(r)">
            Download {{ r.fileName }}
          </button>
        </div>
        <textarea class="script" readonly spellcheck="false" aria-label="Onboarding script">{{
          r.script
        }}</textarea>
      } @else {
        <p class="hint">
          The script sets this router up for the platform (docs/06): the tunnel to the hub, RADIUS,
          the pools and the restricted-mode rules, the monitoring login. Enter the addressing the
          router uses for its customers; the platform fills in the hub, the pay page and fresh
          secrets.
        </p>
        <p class="warn">
          Generating it replaces the router's WireGuard key, RADIUS secret and REST login: logins
          through this router fail from the moment FreeRADIUS reloads until the router has run the
          script.
        </p>
        <form [formGroup]="form" id="onboarding-form" (ngSubmit)="generate()">
          <mat-form-field appearance="outline">
            <mat-label>PPPoE interface</mat-label>
            <input
              matInput
              formControlName="pppoeInterface"
              placeholder="ether2"
              autocomplete="off"
            />
            <mat-hint>Where customers dial in; an existing PPPoE server on it is kept</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>PPP local address</mat-label>
            <input
              matInput
              formControlName="pppLocalAddress"
              placeholder="10.10.0.1"
              autocomplete="off"
            />
            <mat-hint>The router's own address on customer sessions, also their DNS</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Full pool</mat-label>
            <input
              matInput
              formControlName="poolFull"
              placeholder="10.10.0.10-10.10.0.250"
              autocomplete="off"
            />
            <mat-hint>Addresses for paid-up sessions: a range or a network</mat-hint>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Restricted pool</mat-label>
            <input
              matInput
              formControlName="poolRestricted"
              placeholder="10.11.0.10-10.11.0.250"
              autocomplete="off"
            />
            <mat-hint
              >Addresses for sessions on the pay page; must differ from the full pool</mat-hint
            >
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Management networks</mat-label>
            <input
              matInput
              formControlName="managementCidrs"
              placeholder="192.168.88.0/24"
              autocomplete="off"
            />
            <mat-hint
              >Who may reach the router's REST service beside the hub; comma-separated</mat-hint
            >
          </mat-form-field>
          @if (error(); as error) {
            <p class="error" role="alert">{{ error }}</p>
          }
        </form>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      @if (result()) {
        <button mat-flat-button type="button" (click)="close()">Done</button>
      } @else {
        <button mat-button type="button" mat-dialog-close>Cancel</button>
        <button
          mat-flat-button
          type="submit"
          form="onboarding-form"
          [disabled]="form.invalid || busy()"
        >
          Generate
        </button>
      }
    </mat-dialog-actions>
  `,
})
export class OnboardingScriptDialogComponent {
  protected readonly data = inject<{ router: Router }>(MAT_DIALOG_DATA);
  private readonly routers = inject(RoutersService);
  private readonly ref =
    inject<MatDialogRef<OnboardingScriptDialogComponent, boolean>>(MatDialogRef);
  protected readonly busy = signal(false);
  protected readonly copied = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly result = signal<RouterOnboardingScript | null>(null);
  protected readonly form = inject(NonNullableFormBuilder).group({
    pppoeInterface: [
      this.data.router.pppoeInterface ?? '',
      [Validators.required, Validators.pattern(/^[A-Za-z0-9._-]{1,32}$/)],
    ],
    pppLocalAddress: [
      this.data.router.pppLocalAddress ?? '',
      [Validators.required, Validators.pattern(IPV4)],
    ],
    poolFull: [
      this.data.router.poolFull ?? '',
      [Validators.required, Validators.pattern(RANGE_OR_CIDR)],
    ],
    poolRestricted: [
      this.data.router.poolRestricted ?? '',
      [Validators.required, Validators.pattern(RANGE_OR_CIDR)],
    ],
    managementCidrs: [
      this.data.router.managementCidrs ?? '',
      [Validators.required, Validators.pattern(CIDR_LIST)],
    ],
  });

  protected generate(): void {
    const request: RouterOnboardingRequest = this.form.getRawValue();
    this.busy.set(true);
    this.error.set(null);
    this.routers.onboardingScript(this.data.router.id ?? '', request).subscribe({
      next: (script) => {
        this.result.set(script);
        this.busy.set(false);
      },
      error: (e: unknown) => {
        this.error.set(errorMessage(e));
        this.busy.set(false);
      },
    });
  }

  protected copy(script: string): void {
    void navigator.clipboard.writeText(script).then(() => this.copied.set(true));
  }

  protected download(script: RouterOnboardingScript): void {
    const url = URL.createObjectURL(new Blob([script.script ?? ''], { type: 'text/plain' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = script.fileName ?? 'corebalt-router.rsc';
    a.click();
    URL.revokeObjectURL(url);
  }

  protected close(): void {
    this.ref.close(true);
  }
}
