import { command, submit } from './client';
import { isRecord } from './contract';
import type { components } from './generated/schema';

export type SnAccountCreate = components['schemas']['SnAccountCreateDTO'];
export type SnAccountUpdate = components['schemas']['SnAccountUpdateDTO'];
type Version = Required<components['schemas']['SnAccountVersionVO']>;
function isVersion(value: unknown): value is Version {
  return (
    isRecord(value) && typeof value.version === 'string' && /^[1-9][0-9]*$/.test(value.version)
  );
}
type Saved = components['schemas']['SnAccountSavedVO'];
function isSaved(value: unknown): value is Saved {
  return isRecord(value) && typeof value.id === 'string' && /^[1-9][0-9]*$/.test(value.id);
}

export const snAccountsApi = {
  status: (id: string, input: components['schemas']['SnAccountStatusDTO']) =>
    submit(`/sn/users/${id}/status`, input, isVersion),
  resetPassword: (id: string, input: components['schemas']['SnAccountPasswordResetDTO']) =>
    submit(`/sn/users/${id}/password`, input, isVersion),
  create: (input: SnAccountCreate) => submit('/sn/users', input, isSaved),
  update: (id: string, input: SnAccountUpdate) => command(`/sn/users/${id}/update`, input),
};
