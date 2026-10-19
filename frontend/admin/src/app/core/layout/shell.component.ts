import { BreakpointObserver } from '@angular/cdk/layout';
import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDividerModule } from '@angular/material/divider';
import { MatListModule } from '@angular/material/list';
import { MatMenuModule } from '@angular/material/menu';
import { MatSidenavModule } from '@angular/material/sidenav';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import {
  IconCheck,
  IconDeviceDesktop,
  IconLogout,
  IconMenu2,
  IconMoon,
  IconSun,
  IconUserCircle,
  TablerIconComponent,
} from '@tabler/icons-angular';
import { map } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { PermissionService } from '../permissions/permission.service';
import { ThemeMode, ThemeModeService } from '../theme/theme-mode.service';
import { TenantThemeService } from '../theme/tenant-theme.service';
import { visibleNav } from './nav-items';

/** Narrower than this, the navigation becomes an overlay opened from the menu button. */
const OVERLAY_NAV = '(max-width: 1023.98px)';

/** Application frame: tenant brand, the permission-aware navigation and the routed feature page. */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatSidenavModule,
    MatToolbarModule,
    MatListModule,
    MatButtonModule,
    MatMenuModule,
    MatDividerModule,
    MatTooltipModule,
    TablerIconComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shell.component.html',
  styleUrl: './shell.component.scss',
})
export class ShellComponent {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly tenantTheme = inject(TenantThemeService);
  private readonly permissions = inject(PermissionService);
  protected readonly themeMode = inject(ThemeModeService);

  protected readonly branding = this.tenantTheme.branding;
  protected readonly me = this.auth.me;
  protected readonly groups = computed(() => visibleNav(this.permissions.permissions()));
  protected readonly overlay = toSignal(
    inject(BreakpointObserver)
      .observe(OVERLAY_NAV)
      .pipe(map((state) => state.matches)),
    { initialValue: false },
  );

  protected readonly modes: { mode: ThemeMode; label: string; icon: typeof IconSun }[] = [
    { mode: 'system', label: 'Same as device', icon: IconDeviceDesktop },
    { mode: 'light', label: 'Light', icon: IconSun },
    { mode: 'dark', label: 'Dark', icon: IconMoon },
  ];
  protected readonly modeIcon = computed(
    () => this.modes.find((m) => m.mode === this.themeMode.mode())?.icon ?? IconDeviceDesktop,
  );
  protected readonly icons = {
    menu: IconMenu2,
    user: IconUserCircle,
    logout: IconLogout,
    check: IconCheck,
  };

  protected async signOut(): Promise<void> {
    await this.auth.logout();
    this.tenantTheme.reset();
    await this.router.navigate(['/login']);
  }
}
