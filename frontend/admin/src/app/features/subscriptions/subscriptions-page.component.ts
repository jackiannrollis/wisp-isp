import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Placeholder for the Subscriptions area; real screens come in a later step. */
@Component({
  selector: 'app-subscriptions-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page">
      <h1>Subscriptions</h1>
      <p class="muted">Coming in a later step.</p>
    </section>
  `,
})
export class SubscriptionsPageComponent {}
