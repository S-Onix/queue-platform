/**
 * 자체 점검 — `node test.mjs`. 프레임워크 없음.
 * fetch·setTimeout을 대역으로 바꿔 폴링 루프를 한 틱씩 손으로 돌린다.
 */
import assert from 'node:assert/strict';
import { createQueueClient, pickInterval, withJitter } from './queue-sdk.js';

// ── pacing 구간 선택 ────────────────────────────────────────────────
const pacing = [[50, 2], [1000, 5], [null, 20]];
assert.equal(pickInterval(pacing, 0), 2);
assert.equal(pickInterval(pacing, 50), 2);
assert.equal(pickInterval(pacing, 51), 5);
assert.equal(pickInterval(pacing, 999999), 20);
assert.equal(pickInterval([], 0), 2, '표가 없으면 기본표로 떨어진다');

// ── 지터는 하한 위로만 ──────────────────────────────────────────────
assert.equal(withJitter(20, () => 0), 20000, '하한 = 구간 값 그대로');
assert.ok(withJitter(20, () => 0.999) < 25000, '상한 = 구간 값의 1.25배 미만');
assert.equal(withJitter(2, () => 0), 2000);
assert.ok(withJitter(2, () => 0.999) < 3000, '작은 구간도 최소 1초는 흩어진다');

// ── 폴링 루프 ───────────────────────────────────────────────────────
const flush = () => new Promise((r) => setImmediate(r));
let pending = null;
const realSetTimeout = globalThis.setTimeout;
globalThis.setTimeout = (fn) => { pending = fn; return 1; };
globalThis.clearTimeout = () => { pending = null; };
const step = async () => { const fn = pending; pending = null; fn(); await flush(); };

const res = (status, body, headers = {}) => ({
  ok: status < 400,
  status,
  json: async () => body,
  headers: { get: (k) => headers[k] },
});

const scripted = (...responses) => {
  const calls = [];
  const queue = [...responses];
  return [calls, async (url) => { calls.push(url); return queue.shift(); }];
};

// 순위 역행 → watermark는 단조 증가해야 한다(API 서버 N대, 어피니티 없음)
{
  const [calls, fetchImpl] = scripted(
    res(200, { data: { lastAdmittedSeq: 100, pacing } }),
    res(200, { data: { lastAdmittedSeq: 50, pacing } }),   // 뒤로 간 값
  );
  const ranks = [];
  const c = createQueueClient({
    baseUrl: 'http://x', queueId: 'q1', tokenId: 't1', seq: 160,
    onUpdate: (s) => ranks.push(s.rank), fetchImpl, rand: () => 0,
  });
  c.start(); await flush();
  await step();
  assert.deepEqual(ranks, [60, 60], '역행 응답에도 순위가 커지면 안 된다');
  assert.equal(c.watermark, 100);
  assert.ok(calls.every((u) => u.endsWith('/status')), '차례 전엔 전광판만 본다');
  c.stop();
}

// rank <= 0 → 개인 폴링으로 전환, ready=true면 admitToken 전달 후 종료
{
  const [calls, fetchImpl] = scripted(
    res(200, { data: { lastAdmittedSeq: 200, pacing } }),
    res(200, { data: { ready: false } }),
    res(200, { data: { ready: true, admitToken: 'AT-1' } }),
  );
  let ready = null;
  const c = createQueueClient({
    baseUrl: 'http://x', queueId: 'q1', tokenId: 't1', seq: 160,
    onReady: (r) => { ready = r; }, fetchImpl, rand: () => 0,
  });
  c.start(); await flush();
  await step();
  await step();
  assert.equal(ready?.admitToken, 'AT-1');
  assert.equal(calls[1], 'http://x/api/v1/queues/q1/tokens/t1?seq=160', 'seq는 필수 쿼리다');
  assert.equal(pending, null, 'ready 이후엔 다시 예약하지 않는다');
}

// 429는 자리를 잃은 게 아니다 — Retry-After만큼 쉬고 그대로 이어간다
// 🪤 RateLimitFilter는 봉투 없이 {error:"RL001"}을 쓴다(실측). 봉투만 보면 코드를 놓친다.
{
  const [, fetchImpl] = scripted(
    res(429, { error: 'RL001', retryAfter: 2 }, { 'Retry-After': '2' }),
    res(200, { data: { lastAdmittedSeq: 10, pacing } }),
  );
  const errs = [];
  const c = createQueueClient({
    baseUrl: 'http://x', queueId: 'q1', tokenId: 't1', seq: 160,
    onError: (e) => errs.push(e.code), fetchImpl, rand: () => 0,
  });
  c.start(); await flush();
  assert.deepEqual(errs, ['RL001']);
  assert.ok(pending, '429 뒤에도 폴링은 계속된다');
  await step();
  assert.equal(c.watermark, 10);
  c.stop();
}

globalThis.setTimeout = realSetTimeout;
console.log('ok — 자체 점검 통과');
