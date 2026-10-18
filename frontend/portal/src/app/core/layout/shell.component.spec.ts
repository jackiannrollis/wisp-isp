import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { NAV_ITEMS } from './nav-items';
import { ShellComponent } from './shell.component';

describe('ShellComponent', () => {
  it('lists every feature area in the navigation', async () => {
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
  });
});
