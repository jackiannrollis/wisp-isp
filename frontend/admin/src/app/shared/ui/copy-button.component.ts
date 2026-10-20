import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatTooltipModule } from '@angular/material/tooltip';
import { IconCheck, IconCopy, TablerIconComponent } from '@tabler/icons-angular';

/** A small icon button that copies a value and shows a tick for two seconds. */
@Component({
  selector: 'app-copy-button',
  imports: [MatButtonModule, MatTooltipModule, TablerIconComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <button
      mat-icon-button
      type="button"
      [attr.aria-label]="'Copy ' + label()"
      [matTooltip]="copied() ? 'Copied' : 'Copy'"
      (click)="copy()"
    >
      <tabler-icon [icon]="copied() ? icons.check : icons.copy" [size]="16" />
    </button>
  `,
  styles: `
    button {
      width: 2rem;
      height: 2rem;
      padding: 0.25rem;
    }
  `,
})
export class CopyButtonComponent {
  readonly value = input.required<string>();
  readonly label = input('value');
  protected readonly icons = { copy: IconCopy, check: IconCheck };
  protected readonly copied = signal(false);

  protected async copy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(this.value());
      this.copied.set(true);
      setTimeout(() => this.copied.set(false), 2000);
    } catch {
      // Clipboard refused (insecure origin or permission): the value stays selectable on screen.
    }
  }
}
