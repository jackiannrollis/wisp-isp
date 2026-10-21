import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { IconLogout, TablerIconComponent } from '@tabler/icons-angular';

import { AuthService } from '../auth/auth.service';
import { TenantThemeService } from '../theme/tenant-theme.service';
import { NAV_ITEMS } from './nav-items';

/**
 * The portal frame: the provider's brand, who is signed in, and three destinations. On a phone (most
 * customers) the navigation is a tab bar at the bottom, within reach of the thumb.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, MatButtonModule, TablerIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  private readonly auth = inject(AuthService);
  private readonly theme = inject(TenantThemeService);
  private readonly router = inject(Router);

  protected readonly branding = this.theme.branding;
  protected readonly customer = this.auth.customer;
  protected readonly navItems = NAV_ITEMS;
  protected readonly logoutIcon = IconLogout;

  protected async signOut(): Promise<void> {
    await this.auth.logout();
    this.theme.reset();
    await this.router.navigateByUrl('/login');
  }
}
