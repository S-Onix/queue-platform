/**
 * Queue Platform 대기열 폴링 SDK (브라우저 전용, 의존성 없음).
 *
 * 하는 일은 하나다 — 대기 페이지가 서버를 올바른 간격으로 물어보게 한다.
 * enqueue·admit·verify·complete는 X-API-Key가 필요해 Tenant 서버의 몫이다(SDK에 없다).
 *
 * 계약과 근거는 doc/TENANT_INTEGRATION.md §4·계약 ③.
 */

const DEFAULT_PACING = [[50, 2], [1000, 5], [5000, 10], [10000, 15], [null, 20]];

/** pacing 표에서 내 순위 이하인 첫 구간의 초. */
export function pickInterval(pacing, rank) {
  const tiers = Array.isArray(pacing) && pacing.length ? pacing : DEFAULT_PACING;
  const hit = tiers.find(([max]) => max === null || max === undefined || rank <= max);
  return hit ? hit[1] : tiers[tiers.length - 1][1];
}

/**
 * 지터 — 하한 위로만 늘린다(비대칭).
 *
 * 🪤 빼지 마라. 30만 명이 같은 표를 보고 있어 지터가 없으면 전원이 같은 초에 몰린다.
 * 대칭(±20%)이 아닌 이유: 실효 간격이 등급 하한 아래로 내려가면 폴링 한도(계약 ③)에 더 빨리 닿는다.
 */
export function withJitter(baseSec, rand = Math.random) {
  return (baseSec + rand() * Math.max(1, baseSec / 4)) * 1000;
}

export function createQueueClient(options) {
  const {
    baseUrl,
    queueId,
    tokenId,
    seq,
    onUpdate = () => {},
    onReady = () => {},
    onError = () => {},
    fetchImpl = globalThis.fetch?.bind(globalThis),
    now = () => Date.now(),
    rand = Math.random,
  } = options;

  if (!baseUrl || !queueId || !tokenId || !Number.isFinite(seq)) {
    throw new Error('createQueueClient: baseUrl·queueId·tokenId·seq(숫자)가 모두 필요하다');
  }

  const api = `${baseUrl.replace(/\/$/, '')}/api/v1/queues/${encodeURIComponent(queueId)}`;
  const channelName = `queue:${queueId}:${tokenId}`;

  // 🔴 호출 간에 유지해야 하는 유일한 상태. API 서버가 N대이고 세션 어피니티가 없어
  // 방금 받은 lastAdmittedSeq가 직전 값보다 작을 수 있다 — 그대로 쓰면 순위가 역행한다.
  let watermark = 0;

  // 🔴 개인 폴링을 시작했으면 되돌아가지 않는다. last-active를 심는 것이 개인 폴링이라,
  // 시작해 놓고 멈추면 inactiveTtl(300초) 회수 대상이 된다.
  let personal = false;

  let running = false;
  let timer = null;
  let channel = null;

  const publish = (msg) => { try { channel?.postMessage(msg); } catch { /* 닫힌 채널 */ } };

  const emitUpdate = (state) => { onUpdate(state); publish({ type: 'update', state }); };
  const emitReady = (result) => { onReady(result); publish({ type: 'ready', result }); };

  async function getJson(url) {
    const res = await fetchImpl(url, { headers: { Accept: 'application/json' } });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) {
      const err = new Error(`HTTP ${res.status}`);
      err.status = res.status;
      // 🪤 429가 두 군데서 나오고 모양이 다르다. RateLimitFilter는 봉투 없이 {error, message,
      // retryAfter}를 직접 쓰고(RL001), 애플리케이션 예외는 ApiResponse 봉투를 탄다(Q005).
      err.code = body?.errorResponse?.code ?? body?.error;
      err.retryAfterMs = Number(res.headers?.get?.('Retry-After') || 0) * 1000;
      throw err;
    }
    return body?.data ?? {};
  }

  /** 전광판 — 평상시엔 이것만 부른다. 인증도 Rate Limit도 없다. */
  async function pollBoard() {
    const data = await getJson(`${api}/status`);
    watermark = Math.max(watermark, Number(data.lastAdmittedSeq) || 0);
    const rank = Math.max(0, seq - watermark);
    emitUpdate({ rank, lastAdmittedSeq: watermark });
    return { rank, waitMs: withJitter(pickInterval(data.pacing, rank), rand) };
  }

  /** 개인 폴링 — 차례가 가까울 때만(rank <= 0). 버킷은 용량 5·초당 1개 충전이다. */
  async function pollPersonal() {
    const url = `${api}/tokens/${encodeURIComponent(tokenId)}?seq=${seq}`;
    const data = await getJson(url);
    if (data.ready) {
      emitReady({ admitToken: data.admitToken, tokenId, queueId });
      return { done: true };
    }
    emitUpdate({ rank: 0, lastAdmittedSeq: watermark });
    return { done: false, waitMs: withJitter(pickInterval(DEFAULT_PACING, 0), rand) };
  }

  async function tick() {
    if (!running) return;
    try {
      let waitMs;
      if (personal) {
        const r = await pollPersonal();
        if (r.done) { stop(); return; }
        waitMs = r.waitMs;
      } else {
        const r = await pollBoard();
        if (r.rank <= 0) personal = true;
        waitMs = r.waitMs;
      }
      schedule(waitMs);
    } catch (err) {
      onError(err);
      // 429는 자리를 잃은 것이 아니다 — 잠깐 너무 자주 물어본 것뿐이라 그대로 재시도한다.
      schedule(err.retryAfterMs || withJitter(pickInterval(DEFAULT_PACING, 0), rand));
    }
  }

  function schedule(ms) {
    if (!running) return;
    timer = setTimeout(tick, ms);
  }

  /**
   * 🔑 탭 하나만 폴링하게 만든다 — 폴링 버킷 키가 tokenId 하나라 탭 2개면 여유가 0이다(계약 ③).
   *
   * 리더 선출은 Web Locks에 맡긴다. 탭이 죽으면 브라우저가 락을 자동으로 놓아
   * 하트비트·타임아웃 없이 다음 탭이 이어받는다. 팔로워는 BroadcastChannel로 화면만 갱신한다.
   * 둘 중 하나라도 없는 환경이면 그냥 혼자 폴링한다(= 단일 탭과 같은 동작).
   */
  let stopLeading = null;

  function start() {
    if (running) return;
    running = true;

    if (typeof BroadcastChannel === 'function') {
      channel = new BroadcastChannel(channelName);
      channel.onmessage = ({ data: msg }) => {
        if (msg?.type === 'update') onUpdate(msg.state);
        else if (msg?.type === 'ready') { onReady(msg.result); stop(); }
      };
    }

    if (navigator?.locks?.request) {
      navigator.locks.request(channelName, () => new Promise((release) => {
        if (!running) return release();
        tick();
        stopLeading = release;   // stop()이 락을 놓아 다음 탭이 이어받게 한다
      }));
    } else {
      tick();
    }
  }

  function stop() {
    running = false;
    clearTimeout(timer);
    timer = null;
    stopLeading?.(); stopLeading = null;
    channel?.close(); channel = null;
  }

  return { start, stop, get watermark() { return watermark; } };
}
