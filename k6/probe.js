// 포화점 탐색 — RPS 를 계단식으로 올리며 무릎을 찾는다.
//
// 연속 램프가 아니라 계단인 이유: 각 단계에서 안정된 측정을 얻어야
// "이 RPS 에서 p99 가 얼마" 를 말할 수 있다. 연속 램프는 매 순간이 과도상태다.
import http from 'k6/http';
import { Rate, Trend } from 'k6/metrics';
import { zipfSampler, keyName } from './zipf.js';

const TARGET = __ENV.TARGET || 'http://192.168.55.164';
const N      = parseInt(__ENV.KEYSPACE);
const SKEW   = parseFloat(__ENV.SKEW);
const STEPS  = (__ENV.STEPS || '1000,2000,4000,8000,16000,32000').split(',').map(Number);
const STEP_S = parseInt(__ENV.STEP_SECONDS || '20');

const cacheHit = new Rate('cache_hit');

// 단계마다 별도 시나리오 → k6 요약이 단계별로 분리돼 나온다
const scenarios = {};
STEPS.forEach((rps, i) => {
  scenarios[`s${rps}`] = {
    executor: 'constant-arrival-rate',
    rate: rps, timeUnit: '1s', duration: `${STEP_S}s`,
    preAllocatedVUs: Math.min(3000, Math.max(100, rps)),
    maxVUs: 6000,
    startTime: `${i * (STEP_S + 5)}s`,     // 단계 사이 5초 배수
    tags: { step: String(rps) },
    gracefulStop: '3s',
  };
});

export const options = { scenarios, discardResponseBodies: false };

const sampler = zipfSampler(N, SKEW, 42 + __VU);

export default function () {
  const res = http.get(`${TARGET}/key/${keyName(sampler())}`, { tags: { name: 'key' } });
  if (res.status === 200) cacheHit.add(res.json().cached);
}
