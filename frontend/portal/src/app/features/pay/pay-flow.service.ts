import { DestroyRef, Injectable, inject, signal } from '@angular/core';
import {
  EMPTY,
  Observable,
  Subscription,
  catchError,
  filter,
  first,
  forkJoin,
  map,
  switchMap,
  take,
  takeUntil,
  timer,
} from 'rxjs';

import { errorMessage, newIdempotencyKey } from '../../core/api/requests';
import { Money, StkRequest } from '../../shared/models';
import { LinesService } from '../status/data/lines.service';
import { StkService } from './data/stk.service';

/** What one press of Pay Now pays: an amount, and the limited lines whose access to watch. */
export interface PayTarget {
  label: string;
  amount: Money;
  /** Lines that were not FULL before paying; "Internet restored" waits for each of them. */
  watch: string[];
}

export type PayPhase =
  | 'idle'
  | 'sending'
  | 'waiting'
  | 'restoring'
  | 'restored'
  | 'paid'
  | 'restart'
  | 'failed'
  | 'timedOut'
  | 'noAnswer';

/** How often the page asks; the STK request and the restore status are both cheap reads. */
export const POLL_MS = 3_000;
/** docs/06, decisions.md 2026-10-20: "restart your router" after 90 seconds without a restore. */
export const RESTORE_FALLBACK_MS = 90_000;
/** P6: the server times a push out after 5 minutes; the page stops asking a little later. */
export const STK_GIVE_UP_MS = 6 * 60_000;

/**
 * One payment from the pay page (docs/06 Restricted mode, P6): an STK Push to the customer's phone,
 * then its outcome, then, for lines that were limited, the wait until the router has the new profile.
 * `since` for the restore check is the STK request's own createdAt (the server's clock, at or before
 * the payment), so every session from before the payment counts.
 */
@Injectable()
export class PayFlow {
  private readonly stk = inject(StkService);
  private readonly lines = inject(LinesService);

  readonly phase = signal<PayPhase>('idle');
  readonly target = signal<PayTarget | null>(null);
  readonly error = signal<string | null>(null);
  /** The provider's reason when M-Pesa refused or the customer cancelled. */
  readonly resultDesc = signal<string | null>(null);

  private key = '';
  private running: Subscription | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** A new press: a new Idempotency-Key. */
  pay(target: PayTarget): void {
    this.key = newIdempotencyKey();
    this.target.set(target);
    this.send();
  }

  /** The same press again after a failed request: the same key, so the phone is pushed once. */
  retry(): void {
    if (this.target()) {
      this.send();
    }
  }

  reset(): void {
    this.stop();
    this.phase.set('idle');
    this.target.set(null);
    this.error.set(null);
    this.resultDesc.set(null);
  }

  private send(): void {
    const target = this.target();
    if (!target) {
      return;
    }
    this.stop();
    this.phase.set('sending');
    this.error.set(null);
    this.resultDesc.set(null);
    this.running = this.stk.payNow(target.amount, this.key).subscribe({
      next: (request) => this.await(request, target),
      error: (e: unknown) => {
        this.phase.set('idle');
        this.error.set(errorMessage(e, 'The payment request did not go through. Try again.'));
      },
    });
  }

  /** Asks for the STK request until M-Pesa answers, the server times it out, or the page gives up. */
  private await(request: StkRequest, target: PayTarget): void {
    this.phase.set('waiting');
    const id = request.id ?? '';
    this.running = timer(0, POLL_MS)
      .pipe(
        // A failed poll (a network blip) is skipped; the next one asks again.
        switchMap(() => this.stk.request(id).pipe(catchError(() => EMPTY))),
        filter((r) => r.status !== 'PENDING'),
        first(),
        takeUntil(timer(STK_GIVE_UP_MS)),
      )
      .subscribe({
        next: (r) => this.answered(r, target, request.createdAt ?? new Date().toISOString()),
        error: () => this.phase.set('noAnswer'),
        complete: () => {
          if (this.phase() === 'waiting') {
            this.phase.set('noAnswer');
          }
        },
      });
  }

  private answered(request: StkRequest, target: PayTarget, since: string): void {
    switch (request.status) {
      case 'COMPLETED':
        if (target.watch.length === 0) {
          this.phase.set('paid');
        } else {
          this.restore(target.watch, since);
        }
        return;
      case 'TIMED_OUT':
        this.phase.set('timedOut');
        return;
      default:
        this.resultDesc.set(request.resultDesc ?? null);
        this.phase.set('failed');
    }
  }

  /** "Internet restored" once every watched line says so; the router fallback after 90 seconds. */
  private restore(lineIds: string[], since: string): void {
    this.phase.set('restoring');
    const allRestored: Observable<boolean> = timer(0, POLL_MS).pipe(
      switchMap(() =>
        forkJoin(lineIds.map((id) => this.lines.restoreStatus(id, since))).pipe(
          map((statuses) => statuses.every((s) => s.restored)),
          catchError(() => EMPTY),
        ),
      ),
    );
    this.running = allRestored
      .pipe(filter(Boolean), take(1), takeUntil(timer(RESTORE_FALLBACK_MS)))
      .subscribe({
        next: () => this.phase.set('restored'),
        error: () => this.phase.set('restart'),
        complete: () => {
          if (this.phase() === 'restoring') {
            this.phase.set('restart');
          }
        },
      });
  }

  private stop(): void {
    this.running?.unsubscribe();
    this.running = null;
  }
}
