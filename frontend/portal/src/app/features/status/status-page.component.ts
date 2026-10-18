import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Placeholder for the My status area; real screens come in a later step. */
@Component({
  selector: 'app-status-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page">
      <h1>My status</h1>
      <p class="muted">Coming in a later step.</p>
    </section>
  `,
})
export class StatusPageComponent {}
