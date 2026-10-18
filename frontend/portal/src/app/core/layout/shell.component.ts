import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { TenantThemeService } from '../theme/tenant-theme.service';
import { NAV_ITEMS } from './nav-items';

/** Application frame: tenant brand, navigation and the routed feature page. */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  protected readonly branding = inject(TenantThemeService).branding;
  protected readonly navItems = NAV_ITEMS;
  protected readonly appLabel = 'Customer portal';
}
