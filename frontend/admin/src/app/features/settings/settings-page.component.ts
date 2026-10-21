import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { MatTabsModule } from '@angular/material/tabs';

import { PermissionService } from '../../core/permissions/permission.service';
import { PageHeaderComponent } from '../../shared/ui/page-header.component';
import { AccountSettingsComponent } from './account-settings.component';
import { MpesaSettingsComponent } from './mpesa-settings.component';
import { SmsSettingsComponent } from './sms-settings.component';
import { TemplatesComponent } from './templates.component';

/**
 * Settings by permission: the account's own policy and branding (settings.write, Owner only), M-Pesa
 * (settings.secrets), the SMS account (seen with
 * settings.notifications, changed with settings.secrets) and the message templates
 * (settings.notifications). A tab the user cannot use is not shown; the API refuses it anyway.
 */
@Component({
  selector: 'app-settings-page',
  imports: [
    MatTabsModule,
    PageHeaderComponent,
    AccountSettingsComponent,
    MpesaSettingsComponent,
    SmsSettingsComponent,
    TemplatesComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header
      title="Settings"
      subtitle="How payments reach you and how customers hear from you."
    />
    <mat-tab-group animationDuration="0ms" mat-stretch-tabs="false">
      @if (account()) {
        <mat-tab label="Account">
          <ng-template matTabContent><app-account-settings /></ng-template>
        </mat-tab>
      }
      @if (secrets()) {
        <mat-tab label="M-Pesa">
          <ng-template matTabContent><app-mpesa-settings /></ng-template>
        </mat-tab>
      }
      @if (notifications() || account()) {
        <mat-tab label="SMS account">
          <ng-template matTabContent><app-sms-settings /></ng-template>
        </mat-tab>
      }
      @if (notifications()) {
        <mat-tab label="Messages">
          <ng-template matTabContent><app-templates /></ng-template>
        </mat-tab>
      }
    </mat-tab-group>
  `,
})
export class SettingsPageComponent {
  private readonly permissions = inject(PermissionService);
  protected readonly account = computed(() => this.permissions.permissions().has('settings.write'));
  protected readonly secrets = computed(() =>
    this.permissions.permissions().has('settings.secrets'),
  );
  protected readonly notifications = computed(() =>
    this.permissions.permissions().has('settings.notifications'),
  );
}
