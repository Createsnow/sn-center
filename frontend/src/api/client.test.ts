import { describe, expect, it } from 'vitest';
import { ApiError, clientErrors, isDecimalString, parseNothing, unwrap } from './client';

const traceId = '0123456789abcdef0123456789abcdef';

function envelope(overrides: Record<string, unknown> = {}) {
  return { code: 0, key: 'success', args: {}, data: null, traceId, ...overrides };
}

function captured(action: () => unknown): ApiError {
  try {
    action();
  } catch (error) {
    if (error instanceof ApiError) {
      return error;
    }
    throw error;
  }
  throw new Error('Expected ApiError');
}

describe('unwrap', () => {
  it('returns parsed data for a successful envelope', () => {
    const id = unwrap(200, envelope({ data: '9007199254740993' }), (data) => {
      if (!isDecimalString(data)) throw new TypeError('id');
      return data;
    });
    expect(id).toBe('9007199254740993');
    expect(unwrap(200, envelope(), parseNothing)).toBeNull();
  });

  it('raises the server error code, key, arguments and trace id', () => {
    const error = captured(() =>
      unwrap(
        409,
        envelope({ code: 1000000006, key: 'error.common.conflict', args: { name: 'a' } }),
        parseNothing,
      ),
    );
    expect(error).toMatchObject({
      status: 409,
      code: 1000000006,
      key: 'error.common.conflict',
      args: { name: 'a' },
      traceId,
    });
  });

  it('treats a success code with an error status as a failure', () => {
    expect(captured(() => unwrap(500, envelope(), parseNothing)).status).toBe(500);
  });

  it.each([
    ['non-object', 'oops'],
    ['missing data', { code: 0, key: 'success', args: {}, traceId }],
    ['numeric argument', envelope({ args: { size: 10 } })],
    ['fractional code', envelope({ code: 1.5 })],
  ])('rejects an invalid envelope: %s', (_, body) => {
    expect(captured(() => unwrap(200, body, parseNothing)).key).toBe(
      clientErrors.invalidResponse.key,
    );
  });

  it('rejects data that fails the caller parser', () => {
    const error = captured(() => unwrap(200, envelope({ data: 42 }), parseNothing));
    expect(error.key).toBe(clientErrors.invalidResponse.key);
    expect(error.traceId).toBe(traceId);
  });
});

describe('isDecimalString', () => {
  it('accepts only canonical non-negative decimal strings', () => {
    expect(isDecimalString('0')).toBe(true);
    expect(isDecimalString('18446744073709551615')).toBe(true);
    expect(isDecimalString('01')).toBe(false);
    expect(isDecimalString(1)).toBe(false);
    expect(isDecimalString('-1')).toBe(false);
  });
});
