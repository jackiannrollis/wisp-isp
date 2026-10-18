import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Placeholder for the Dashboard area; real screens come in a later step. */
@Component({
  selector: 'app-dashboard-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page">
      <h1>Dashboard</h1>
      <p class="muted">Coming in a later step.</p>
    </section>
  `,
})
export class DashboardPageComponent {}
