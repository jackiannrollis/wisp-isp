import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { IconHammer, TablerIconComponent } from '@tabler/icons-angular';

import { PageHeaderComponent } from '../ui/page-header.component';

/** A navigation target whose screen is built later in step 8; `heading` comes from the route data. */
@Component({
  selector: 'app-placeholder-page',
  imports: [PageHeaderComponent, TablerIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <app-page-header [title]="heading()" />
    <div class="panel state">
      <tabler-icon [icon]="icon" [size]="22" class="icon" />
      <p class="title">This screen is not built yet</p>
      <p class="muted">It arrives in a later part of step 8.</p>
    </div>
  `,
  styles: `
    .state {
      display: flex;
      flex-direction: column;
      align-items: center;
      gap: var(--app-space-2);
      padding: var(--app-space-6) var(--app-space-4);
      text-align: center;
    }
    .icon {
      color: var(--mat-sys-on-surface-variant);
    }
    .title {
      margin: 0;
      font: var(--mat-sys-title-small);
    }
    .muted {
      margin: 0;
    }
  `,
})
export class PlaceholderPageComponent {
  readonly heading = input('');
  protected readonly icon = IconHammer;
}
