import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Placeholder for the Settings area; real screens come in a later step. */
@Component({
  selector: 'app-settings-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page">
      <h1>Settings</h1>
      <p class="muted">Coming in a later step.</p>
    </section>
  `,
})
export class SettingsPageComponent {}
