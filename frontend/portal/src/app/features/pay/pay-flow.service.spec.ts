import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { POLL_MS, PayFlow, RESTORE_FALLBACK_MS, STK_GIVE_UP_MS } from './pay-flow.service';

const AMOUNT = { amount: 250_000, currency: 'KES' };
const CREATED = '2026-10-21T10:00:00Z';

describe('PayFlow', () => {
  let flow: PayFlow;
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [PayFlow, provideHttpClient(), provideHttpClientTesting()],
    });
    flow = TestBed.inject(PayFlow);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    http.verify();
    vi.useRealTimers();
  });

  function push(watch: string[] = ['line-1']): string {
    flow.pay({ label: 'Home 10', amount: AMOUNT, watch });
    const post = http.expectOne('/api/v1/portal/stk');
    const key = post.request.headers.get('Idempotency-Key') ?? '';
    expect(post.request.body).toEqual({ amount: AMOUNT });
    post.flush({ id: 'stk-1', status: 'PENDING', createdAt: CREATED });
    return key;
  }

  function stkPoll(status: string, resultDesc?: string): void {
    http.expectOne('/api/v1/portal/stk/stk-1').flush({ id: 'stk-1', status, resultDesc });
  }

  function restorePoll(restored: boolean, line = 'line-1'): void {
    const req = http.expectOne((r) => r.url === `/api/v1/portal/lines/${line}/restore-status`);
    expect(req.request.params.get('since')).toBe(CREATED);
    req.flush({ accessProfile: restored ? 'FULL' : 'RESTRICTED', restored });
  }

  it('P6_waitsForThePromptThenShowsInternetRestoredOnceTheLineIsBack', () => {
    push();
    vi.advanceTimersByTime(0);
    stkPoll('PENDING');
    expect(flow.phase()).toBe('waiting');
    vi.advanceTimersByTime(POLL_MS);
    stkPoll('COMPLETED');
    expect(flow.phase()).toBe('restoring');
    vi.advanceTimersByTime(0);
    restorePoll(false);
    vi.advanceTimersByTime(POLL_MS);
    restorePoll(true);
    expect(flow.phase()).toBe('restored');
    vi.advanceTimersByTime(POLL_MS * 3);
    http.expectNone((r) => r.url.includes('restore-status'));
  });

  it('docs06_showsRestartYourRouterAfter90SecondsWithoutARestore', () => {
    push();
    vi.advanceTimersByTime(0);
    stkPoll('COMPLETED');
    vi.advanceTimersByTime(0);
    restorePoll(false);
    for (let at = POLL_MS; at < RESTORE_FALLBACK_MS; at += POLL_MS) {
      vi.advanceTimersByTime(POLL_MS);
      restorePoll(false);
    }
    vi.advanceTimersByTime(POLL_MS);
    expect(flow.phase()).toBe('restart');
    http.expectNone((r) => r.url.includes('restore-status'));
  });

  it('a payment for a line already at full speed needs no restore wait', () => {
    push([]);
    vi.advanceTimersByTime(0);
    stkPoll('COMPLETED');
    expect(flow.phase()).toBe('paid');
  });

  it('pay all waits for every limited line', () => {
    push(['line-1', 'line-2']);
    vi.advanceTimersByTime(0);
    stkPoll('COMPLETED');
    vi.advanceTimersByTime(0);
    restorePoll(true, 'line-1');
    restorePoll(false, 'line-2');
    expect(flow.phase()).toBe('restoring');
    vi.advanceTimersByTime(POLL_MS);
    restorePoll(true, 'line-1');
    restorePoll(true, 'line-2');
    expect(flow.phase()).toBe('restored');
  });

  it('P6_showsWhyMPesaRefusedAndThatATimeoutIsNeverRetried', () => {
    push();
    vi.advanceTimersByTime(0);
    stkPoll('FAILED', 'Request cancelled by user');
    expect(flow.phase()).toBe('failed');
    expect(flow.resultDesc()).toBe('Request cancelled by user');

    flow.reset();
    push();
    vi.advanceTimersByTime(0);
    stkPoll('TIMED_OUT');
    expect(flow.phase()).toBe('timedOut');
    vi.advanceTimersByTime(POLL_MS * 3);
    http.expectNone('/api/v1/portal/stk');
  });

  it('a failed poll is skipped, and the page gives up asking after the server timeout', () => {
    push();
    vi.advanceTimersByTime(0);
    http
      .expectOne('/api/v1/portal/stk/stk-1')
      .flush(null, { status: 0, statusText: 'Network error' });
    expect(flow.phase()).toBe('waiting');
    for (let at = POLL_MS; at < STK_GIVE_UP_MS; at += POLL_MS) {
      vi.advanceTimersByTime(POLL_MS);
      stkPoll('PENDING');
    }
    vi.advanceTimersByTime(POLL_MS);
    expect(flow.phase()).toBe('noAnswer');
  });

  it('Idempotency_aRetryOfTheSamePressReusesItsKeyAndANewPressDoesNot', () => {
    flow.pay({ label: 'Home 10', amount: AMOUNT, watch: [] });
    const first = http.expectOne('/api/v1/portal/stk');
    const key = first.request.headers.get('Idempotency-Key');
    first.flush(null, { status: 0, statusText: 'Network error' });
    expect(flow.phase()).toBe('idle');
    expect(flow.error()).toContain('cannot be reached');

    flow.retry();
    const again = http.expectOne('/api/v1/portal/stk');
    expect(again.request.headers.get('Idempotency-Key')).toBe(key);
    again.flush({ id: 'stk-1', status: 'PENDING', createdAt: CREATED });
    flow.reset();

    expect(push()).not.toBe(key);
    flow.reset();
  });
});
