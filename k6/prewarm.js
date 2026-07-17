// ─────────────────────────────────────────────────────────────────────────
// 결정적 프리웜 — 모든 키를 정확히 한 번씩 훑는다.
//
// 왜 필요한가:
//   비율 ≤ 1 에서 arm A 는 축출을 하지 않는다(용량 ≥ 키 공간). 그래서 A 의 미스는
//   "처음 보는 키"일 때만 나고, 히트율은 **쿠폰 수집가 문제**로 100% 에 점근한다.
//   Zipf 꼬리의 차가운 키가 좀처럼 안 뽑혀서 수렴이 극도로 느리다.
//   (실측: 500 RPS × 80s = 40,000 요청에도 10,000 키 중 6,638 개만 캐시에 들어옴)
//
//   수렴 전에 측정하면 A 가 과소평가되고 → 손실(A−B)도 과소평가된다.
//   **우리 가설에 불리한 방향의 편향**이므로 반드시 제거해야 한다.
//
// 이건 치팅이 아니다: 우리가 재려는 건 **정상상태** 히트율이고, 프리웜은 거기 빨리
// 도달할 뿐 도착점을 바꾸지 않는다. 세 arm 에 똑같이 적용된다.
// 프리웜 후에도 Zipf 워밍업을 돌려 LRU 가 핫셋 순서를 정리하게 한다.
import http from 'k6/http';
import exec from 'k6/execution';
import { keyName } from './zipf.js';

const TARGET = __ENV.TARGET || 'http://192.168.55.164';
const N = parseInt(__ENV.KEYSPACE);
if (!N) throw new Error('KEYSPACE 필수');

export const options = {
  scenarios: {
    prewarm: {
      executor: 'shared-iterations',
      vus: 50,
      iterations: N,          // 키 하나당 정확히 1회
      maxDuration: '600s',
    },
  },
  discardResponseBodies: true,
};

export default function () {
  // iterationInTest 는 시나리오 전체의 전역 인덱스 — VU 가 여럿이어도 0..N-1 을 정확히 한 번씩.
  // nginx 를 통과하므로 arm 별 라우팅(RR / hash)이 그대로 적용된다.
  http.get(`${TARGET}/key/${keyName(exec.scenario.iterationInTest)}`);
}
