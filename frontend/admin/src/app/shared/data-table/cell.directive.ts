import { Directive, TemplateRef, inject, input } from '@angular/core';

/** The template context of a custom cell: the row as `$implicit`. */
export interface CellContext<T> {
  $implicit: T;
}

/**
 * A custom cell for one column of `<app-data-table>`:
 * `<ng-template appCell="status" let-row>...</ng-template>`.
 */
@Directive({ selector: 'ng-template[appCell]' })
export class CellDirective<T = unknown> {
  readonly appCell = input.required<string>();
  readonly template = inject<TemplateRef<CellContext<T>>>(TemplateRef);
}
