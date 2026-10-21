import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AuthService } from '../auth/auth.service';
import { NAV_ITEMS } from './nav-items';
import { ShellComponent } from './shell.component';

describe('ShellComponent', () => {
  it('lists every destination and who is signed in', async () => {
    TestBed.configureTestingModule({
      imports: [ShellComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    const fixture = TestBed.createComponent(ShellComponent);
    await fixture.whenStable();
    const links = (fixture.nativeElement as HTMLElement).querySelectorAll('nav a');
    expect(Array.from(links, (a) => a.textContent?.trim())).toEqual(
      NAV_ITEMS.map((item) => item.label),
    );
    expect((fixture.nativeElement as HTMLElement).querySelector('.customer')).toBeNull();
    expect(TestBed.inject(AuthService).customer()).toBeNull();
  });
});
