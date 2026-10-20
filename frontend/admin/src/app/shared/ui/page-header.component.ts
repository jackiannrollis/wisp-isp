import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { IconChevronLeft, TablerIconComponent } from '@tabler/icons-angular';

/**
 * The one page header: an optional back link, the title (with anything projected beside it, such
 * as a status), an optional subtitle, and the page's actions on the right, the primary one last.
 * `<app-page-header title="Customers"><button actions ...>New customer</button></app-page-header>`.
 */
@Component({
  selector: 'app-page-header',
  imports: [RouterLink, TablerIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (backLink(); as link) {
      <a class="back" [routerLink]="link">
        <tabler-icon [icon]="backIcon" [size]="16" />{{ backLabel() }}
      </a>
    }
    <div class="row">
      <div class="titles">
        <div class="title-line">
          <h1>{{ title() }}</h1>
          <ng-content select="[status]" />
        </div>
        @if (subtitle()) {
          <p class="subtitle">{{ subtitle() }}</p>
        }
        <ng-content select="[meta]" />
      </div>
      <div class="actions"><ng-content select="[actions]" /></div>
    </div>
  `,
  styles: `
    :host {
      display: block;
      margin-bottom: var(--app-space-5);
    }
    .back {
      display: inline-flex;
      align-items: center;
      gap: 0.125rem;
      margin: 0 0 var(--app-space-2) -0.25rem;
      padding: 0.125rem 0.25rem;
      font: var(--mat-sys-label-large);
      color: var(--mat-sys-on-surface-variant);
      text-decoration: none;
    }
    .back:hover {
      color: var(--mat-sys-on-surface);
    }
    .row {
      display: flex;
      flex-wrap: wrap;
      align-items: flex-start;
      justify-content: space-between;
      gap: var(--app-space-3) var(--app-space-4);
    }
    .title-line {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--app-space-3);
    }
    h1 {
      font: var(--mat-sys-headline-small);
      font-weight: 600;
      letter-spacing: -0.01em;
      margin: 0;
    }
    .subtitle {
      margin: var(--app-space-1) 0 0;
      color: var(--mat-sys-on-surface-variant);
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: var(--app-space-2);
    }
    .actions:empty {
      display: none;
    }
  `,
})
export class PageHeaderComponent {
  readonly title = input.required<string>();
  readonly subtitle = input<string | null>(null);
  readonly backLink = input<string | null>(null);
  readonly backLabel = input('Back');
  protected readonly backIcon = IconChevronLeft;
}
