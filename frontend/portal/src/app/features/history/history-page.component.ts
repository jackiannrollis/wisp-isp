import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Placeholder for the Payment history area; real screens come in a later step. */
@Component({
  selector: 'app-history-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page">
      <h1>Payment history</h1>
      <p class="muted">Coming in a later step.</p>
    </section>
  `,
})
export class HistoryPageComponent {}
