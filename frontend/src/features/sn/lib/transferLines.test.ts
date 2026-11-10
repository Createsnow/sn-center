import { assert, test } from 'vitest';
import {
  type Group,
  type GroupCfg,
  type SrcRow,
  activeGroups,
  autoSeq,
  candidates,
  defaultCfg,
  groupKey,
  groupsOf,
  lineIssue,
  linesBody,
  selectedQty,
} from './transferLines';

const ROWS: SrcRow[] = [
  {
    materialCode: 'M1',
    attachNo: '1',
    factoryCode: 'A',
    qty: 5,
    customers: ['C1'],
    attachNos: ['1'],
    lengths: ['100'],
  },
  {
    materialCode: 'M1',
    attachNo: '2',
    factoryCode: 'A',
    qty: 3,
    customers: ['C1'],
    attachNos: ['2'],
    lengths: ['100'],
  },
  {
    materialCode: 'M2',
    attachNo: '1',
    factoryCode: 'A',
    qty: 4,
    customers: ['C1'],
    attachNos: ['1'],
    lengths: ['200'],
  },
  {
    materialCode: 'M2',
    attachNo: '1',
    factoryCode: 'B',
    qty: 9,
    customers: ['C1'],
    attachNos: ['1'],
    lengths: ['200'],
  },
];
const TARGET = [
  { seq: 1, materialCode: 'M1', attachNo: '1', length: '100' },
  { seq: 2, materialCode: 'M1', attachNo: '2', length: '100' },
  { seq: 3, materialCode: 'M1', attachNo: '2', length: '100' },
  { seq: 4, materialCode: 'M2', attachNo: '1', length: '300' },
  { seq: 5, materialCode: 'M2', attachNo: '1', length: '200' },
];
const cfg = (x: Partial<GroupCfg> = {}): GroupCfg => ({ ...defaultCfg(), ...x });

test('material code and attachment item are grouped at the same level, per source org', () => {
  const gs = groupsOf(ROWS, 'A');
  assert.deepEqual(
    gs.map((g) => [g.materialCode, g.attachNo, g.qty]),
    [
      ['M1', '1', 5],
      ['M1', '2', 3],
      ['M2', '1', 4],
    ],
  );
  assert.equal(groupsOf(ROWS, 'B')[0]!.qty, 9);
  assert.deepEqual(
    groupsOf([{ ...ROWS[0]!, attachNo: undefined }], 'A')[0]!.attachNo,
    '1',
    'older payloads: attachNos',
  );
  const merged = groupsOf(
    [
      { ...ROWS[0]!, seqs: [3] },
      { ...ROWS[0]!, factoryCode: 'A', seqs: [1, 3] },
    ],
    'A',
  );
  assert.deepEqual(merged[0]!.seqs, [1, 3], 'ERP line Nos. of the group, merged and sorted');
  assert.deepEqual(groupsOf(ROWS, 'A')[0]!.seqs, [], 'older payloads without seqs');
});

test('only lines with the same material code and attachment item are candidates', () => {
  const [m1a, m1b, m2] = groupsOf(ROWS, 'A') as [Group, Group, Group];
  assert.deepEqual(
    candidates(TARGET, m1a).map((l) => l.seq),
    [1],
  );
  assert.deepEqual(
    candidates(TARGET, m1b).map((l) => l.seq),
    [2, 3],
  );
  assert.equal(autoSeq(TARGET, m1a, cfg()), 1);
  assert.equal(autoSeq(TARGET, m1b, cfg()), undefined, 'two fitting lines: choose one');
  assert.equal(autoSeq(TARGET, m1b, cfg({ toSeq: 3 })), 3, 'keeps a valid choice');
  assert.equal(autoSeq(TARGET, m2, cfg()), 5, 'picks the only line whose length matches');
  assert.equal(autoSeq(TARGET, m1a, cfg({ toSeq: 4 })), 1, 'drops a line of another material');
});

test('line issues', () => {
  const [m1a, m1b, m2] = groupsOf(ROWS, 'A') as [Group, Group, Group];
  assert.equal(lineIssue('MATERIAL', m1a, cfg({ toSeq: 1 }), TARGET, false), '');
  assert.equal(lineIssue('MATERIAL', m1b, cfg(), TARGET, false), 'PICK_SEQ');
  assert.equal(
    lineIssue('MATERIAL', m1b, cfg(), TARGET, true),
    '',
    'same bill: stays on its own line',
  );
  assert.equal(lineIssue('MATERIAL', m2, cfg(), [TARGET[3]!], false), 'LENGTH');
  assert.equal(lineIssue('MATERIAL', m2, cfg(), [TARGET[0]!], false), 'NO_MATCH');
  assert.equal(
    lineIssue('MATERIAL', m1a, cfg({ mode: 'RANGE', toSeq: 1 }), TARGET, false),
    'NEED_SN',
  );
  assert.equal(
    lineIssue('MATERIAL', m1a, cfg({ mode: 'QTY', qty: 6, toSeq: 1 }), TARGET, false),
    'BAD_QTY',
  );
  assert.equal(
    lineIssue('MATERIAL', m1a, cfg({ mode: 'QTY', qty: 5, toSeq: 1 }), TARGET, false),
    '',
  );
  assert.equal(
    lineIssue('BILL', m1a, cfg({ mode: 'QTY', toSeq: 1 }), TARGET, false),
    '',
    'whole bill ignores the mode',
  );
});

test('request lines and selected quantity', () => {
  const gs = groupsOf(ROWS, 'A');
  const cfgs = {
    [groupKey('M1', '1')]: cfg({ checked: true, mode: 'QTY', qty: 2, toSeq: 1 }),
    [groupKey('M1', '2')]: cfg({
      checked: true,
      mode: 'RANGE',
      startSn: ' S1 ',
      endSn: '',
      toSeq: 3,
    }),
    [groupKey('M2', '1')]: cfg({ checked: false, toSeq: 5 }),
  };
  assert.equal(activeGroups('MATERIAL', gs, cfgs).length, 2);
  assert.deepEqual(linesBody('MATERIAL', gs, cfgs), [
    {
      materialCode: 'M1',
      attachNo: '1',
      mode: 'QTY',
      startSn: null,
      endSn: null,
      qty: 2,
      toSeq: 1,
    },
    {
      materialCode: 'M1',
      attachNo: '2',
      mode: 'RANGE',
      startSn: 'S1',
      endSn: null,
      qty: null,
      toSeq: 3,
    },
  ]);
  assert.equal(selectedQty('MATERIAL', gs, cfgs), 3);
  assert.deepEqual(
    linesBody('BILL', gs, cfgs).map((l) => [l.mode, l.toSeq]),
    [
      ['ALL', 1],
      ['ALL', 3],
      ['ALL', 5],
    ],
  );
  assert.equal(selectedQty('BILL', gs, cfgs), 12);
  cfgs[groupKey('M1', '2')]!.endSn = 'S9';
  assert.equal(selectedQty('MATERIAL', gs, cfgs), null, 'a range is counted in the confirm box');
});
