import { describe, it, expect } from 'vitest';
import { isAuditLog, isAuditDetail } from './audit';
const event = {
  id: '9007199254743001',
  userId: '9007199254741004',
  actorDeptId: null,
  actorUsername: 'reader',
  moduleName: '请求安全',
  businessType: 'OTHER',
  action: 'http:REJECT',
  objectId: null,
  success: false,
  traceId: 'a01bfc76-dc99-4682-bd72-cec56925f23a',
  createTime: '2026-09-29T10:00:00Z',
  requestMethod: 'POST',
  requestRoute: '/api/v1/system/auth/password',
  httpStatus: 400,
  errorCode: 1002000003,
};
describe('audit contracts', () => {
  it('keeps identifiers exact and requires typed result, status and time', () => {
    expect(isAuditLog(event)).toBe(true);
    expect(isAuditLog({ ...event, id: Number(event.id) })).toBe(false);
    expect(isAuditLog({ ...event, success: 'false' })).toBe(false);
    expect(isAuditLog({ ...event, httpStatus: 999 })).toBe(false);
    expect(isAuditLog({ ...event, createTime: 'invalid' })).toBe(false);
  });
  it('accepts nullable history and rejects raw nested diff objects or missing redaction state', () => {
    const detail = {
      event,
      changes: [{ field: 'roleIds', before: null, after: '9007199254741001' }],
      reason: null,
      failureCode: null,
      redacted: true,
    };
    expect(isAuditDetail(detail)).toBe(true);
    expect(
      isAuditDetail({
        ...detail,
        changes: [{ field: 'roleIds', before: { raw: 'value' }, after: [] }],
      }),
    ).toBe(false);
    expect(isAuditDetail({ ...detail, redacted: undefined })).toBe(false);
  });
});
