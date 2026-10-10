import { command, submit } from './client';
import { isRecord } from './contract';
import type { components } from './generated/schema';

export type SnAccountCreate = components['schemas']['SnAccountCreateDTO'];
export type SnAccountUpdate = components['schemas']['SnAccountUpdateDTO'];
type Saved = components['schemas']['SnAccountSavedVO'];
function isSaved(value: unknown): value is Saved {
  return isRecord(value) && typeof value.id === 'string' && /^[1-9][0-9]*$/.test(value.id);
}

export const snAccountsApi = {
  create: (input: SnAccountCreate) => submit('/sn/users', input, isSaved),
  update: (id: string, input: SnAccountUpdate) => command(`/sn/users/${id}/update`, input),
};
