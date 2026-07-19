// 포화점 탐색 — RPS 를 계단식으로 올리며 무릎을 찾는다.
//
// 연속 램프가 아니라 계단인 이유: 각 단계에서 안정된 측정을 얻어야
// "이 RPS 에서 p99 가 얼마" 를 말할 수 있다. 연속 램프는 매 순간이 과도상태다.
import http from 'k6/http';
import { Rate, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { buildCdf, samplerFromCdf, keyName } from './zipf.js';

const TARGET = __ENV.TARGET || 'http://192.168.55.164';
const N      = parseInt(__ENV.KEYSPACE);
const SKEW   = parseFloat(__ENV.SKEW);
const STEPS  = (__ENV.STEPS || '1000,2000,4000,8000,16000,32000').split(',').map(Number);
const STEP_S = parseInt(__ENV.STEP_SECONDS || '20');

const cacheHit = new Rate('cache_hit');

// 단계마다 별도 시나리오 → k6 요약이 단계별로 분리돼 나온다
//
// ⚠️ preAllocatedVUs 는 **시나리오 수만큼 곱해져 초기화 단계에서 전부** 만들어진다.
//    계단 5개 × 3,000 = 15,000 VU 가 부하 생성기 램을 넘겨 호스트를 얼렸다
//    (2026-07-18, TIMELINE.md 문제 ⑥). VU 수요 = rate × 지연이므로 지연 20ms
//    가정이면 rps/50 이면 충분하고, 포화로 지연이 튀면 maxVUs 까지 늘다가
//    dropped_iterations 로 드러난다 — 그게 바로 우리가 찾는 신호다.
//    포화 측정의 기본 경로는 이 파일이 아니라 **k6 런 분리**다
//    (docs/phases/phase-0-saturation/spec.md §3.1 — load.js 를 RPS 만 바꿔 순차 실행).
const scenarios = {};
STEPS.forEach((rps, i) => {
  scenarios[`s${rps}`] = {
    executor: 'constant-arrival-rate',
    rate: rps, timeUnit: '1s', duration: `${STEP_S}s`,
    preAllocatedVUs: Math.min(500, Math.max(50, Math.ceil(rps / 50))),
    maxVUs: 1500,
    startTime: `${i * (STEP_S + 5)}s`,     // 단계 사이 5초 배수
    tags: { step: String(rps) },
    gracefulStop: '3s',
  };
});

export const options = { scenarios, discardResponseBodies: false };

const CDF = new SharedArray('cdf', () => buildCdf(N, SKEW) || []);
const sampler = samplerFromCdf(SKEW === 0 ? null : CDF, N, 42 + __VU);

export default function () {
  const res = http.get(`${TARGET}/key/${keyName(sampler())}`, { tags: { name: 'key' } });
  if (res.status === 200) cacheHit.add(res.json().cached);
}
