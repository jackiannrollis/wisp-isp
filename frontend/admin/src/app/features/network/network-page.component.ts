import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Placeholder for the Network area; real screens come in a later step. */
@Component({
  selector: 'app-network-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page">
      <h1>Network</h1>
      <p class="muted">Coming in a later step.</p>
    </section>
  `,
})
export class NetworkPageComponent {}
