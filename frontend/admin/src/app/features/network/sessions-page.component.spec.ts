import { onlineFor } from './sessions-page.component';

describe('onlineFor', () => {
  const now = Date.parse('2026-10-20T12:00:00Z');

  it('reads how long a session has been up', () => {
    expect(onlineFor('2026-10-20T11:18:00Z', now)).toBe('42m');
    expect(onlineFor('2026-10-17T08:00:00Z', now)).toBe('3d 4h');
    expect(onlineFor(undefined, now)).toBe('—');
  });
});
