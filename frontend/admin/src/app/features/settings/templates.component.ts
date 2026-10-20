import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { Template, SettingsService } from './data/settings.service';
import { TemplateDialogComponent } from './template-dialog.component';
import { templateLabel } from './template-labels';

/**
 * The SMS templates (M1 to M3): every message the platform sends, its wording, when it goes out and
 * how many SMS parts it costs with realistic values. A row opens the editor.
 */
@Component({
  selector: 'app-templates',
  imports: [MatSnackBarModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="help intro">
      Your own wording replaces the default for every customer. Placeholders in braces, such as
      {{ '{' }}name{{ '}' }}, are filled in per customer; they can never carry a PIN, password or ID
      (M3). Messages over one part cost more.
    </p>
    <div class="panel list">
      <table>
        <thead>
          <tr>
            <th>Message</th>
            <th>Wording</th>
            <th>Sent</th>
            <th class="end">Parts</th>
          </tr>
        </thead>
        <tbody>
          @for (t of templates.value(); track t.key) {
            <tr class="link" tabindex="0" (click)="edit(t)" (keydown.enter)="edit(t)">
              <td class="name">
                <span class="label">{{ label(t.key).label }}</span>
                <span class="when">{{ label(t.key).when }}</span>
              </td>
              <td class="body">
                {{ t.body }}
                @if (t.custom) {
                  <span class="custom">Your wording</span>
                }
              </td>
              <td class="sent">{{ t.immediate ? 'At once' : 'In the window' }}</td>
              <td class="end" [class.warn]="(t.segments ?? 1) > 1">{{ t.segments }}</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styleUrl: './templates.component.scss',
})
export class TemplatesComponent {
  private readonly settings = inject(SettingsService);
  private readonly dialog = inject(MatDialog);
  private readonly snack = inject(MatSnackBar);
  private readonly version = signal(0);

  protected readonly label = templateLabel;
  protected readonly templates = rxResource({
    params: () => this.version(),
    stream: () => this.settings.templates(),
  });
  private readonly placeholders = rxResource({ stream: () => this.settings.placeholders() });

  protected edit(template: Template): void {
    this.dialog
      .open(TemplateDialogComponent, {
        data: { template, placeholders: this.placeholders.value() ?? [] },
        width: '40rem',
        maxWidth: '95vw',
      })
      .afterClosed()
      .subscribe((saved: Template | undefined) => {
        if (saved) {
          this.snack.open(`${templateLabel(saved.key).label} saved.`, undefined, {
            duration: 4000,
          });
          this.version.update((v) => v + 1);
        }
      });
  }
}
