import { Directive, TemplateRef, ViewContainerRef, effect, inject, input } from '@angular/core';

import { PermissionService } from '../../core/permissions/permission.service';

/**
 * Renders its template only when the current user holds the permission code:
 * `<button *hasPermission="'customers.read'">`.
 * UI convenience only; the server is the authority on permissions.
 */
@Directive({
  // Named after the permission concept used across the project docs, not the app prefix.
  // eslint-disable-next-line @angular-eslint/directive-selector
  selector: '[hasPermission]',
})
export class HasPermissionDirective {
  private readonly templateRef = inject<TemplateRef<unknown>>(TemplateRef);
  private readonly viewContainer = inject(ViewContainerRef);
  private readonly permissions = inject(PermissionService);

  readonly hasPermission = input.required<string>();

  private rendered = false;

  constructor() {
    effect(() => {
      const allowed = this.permissions.permissions().has(this.hasPermission());
      if (allowed && !this.rendered) {
        this.viewContainer.createEmbeddedView(this.templateRef);
        this.rendered = true;
      } else if (!allowed && this.rendered) {
        this.viewContainer.clear();
        this.rendered = false;
      }
    });
  }
}
