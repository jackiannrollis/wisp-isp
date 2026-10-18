import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Placeholder for the Packages area; real screens come in a later step. */
@Component({
  selector: 'app-packages-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page">
      <h1>Packages</h1>
      <p class="muted">Coming in a later step.</p>
    </section>
  `,
})
export class PackagesPageComponent {}
