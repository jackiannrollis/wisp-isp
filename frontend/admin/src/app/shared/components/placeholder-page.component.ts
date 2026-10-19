import { ChangeDetectionStrategy, Component, input } from '@angular/core';

/** A navigation target whose screen is built later in step 8; `heading` comes from the route data. */
@Component({
  selector: 'app-placeholder-page',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="page">
      <h1>{{ heading() }}</h1>
      <p class="muted">Coming in a later step.</p>
    </section>
  `,
})
export class PlaceholderPageComponent {
  readonly heading = input('');
}
