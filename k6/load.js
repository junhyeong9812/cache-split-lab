// ─────────────────────────────────────────────────────────────────────────
// 부하 시나리오 — APC(192.168.55.9)에서 BPC(192.168.55.164)의 nginx 를 때린다.
//
// constant-arrival-rate (open model) 를 쓴다.
// closed model(ramping-vus)이면 서버가 느려질 때 부하도 같이 줄어드는
// **coordinated omission** 때문에 포화 근처 p99 가 실제보다 좋게 나온다.
// 우리는 포화점 근처를 재므로(Phase 0/4) 치명적이다.
// ─────────────────────────────────────────────────────────────────────────
import http from 'k6/http';
import { Counter, Rate } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import { buildCdf, samplerFromCdf, keyName } from './zipf.js';

const TARGET   = __ENV.TARGET   || 'http://192.168.55.164';
const N        = parseInt(__ENV.KEYSPACE);        // 키 공간 (데이터셋 행 수)
const SKEW     = parseFloat(__ENV.SKEW);          // Zipf s
const RPS      = parseInt(__ENV.RPS);
const DURATION = __ENV.DURATION || '60s';
const SEED     = parseInt(__ENV.SEED || '42');

if (!N || isNaN(SKEW) || !RPS) throw new Error('KEYSPACE / SKEW / RPS 필수 — 조용한 기본값 없음');

// k6 가 본 히트율. 앱 카운터와 **독립적으로** 집계한다 —
// 둘이 어긋나면 둘 중 하나가 틀린 것이다 (교차 검증).
const cacheHit  = new Rate('cache_hit');
const nodeHits  = new Counter('node_hits');       // tag 로 노드별 분해

export const options = {
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: Math.min(2000, Math.max(50, Math.ceil(RPS / 4))),
      maxVUs: 4000,
      gracefulStop: '5s',
    },
  },
  // 에러가 나면 그 구간 지연 지표는 무효다 — 느린 요청이 에러로 빠져
  // p99 가 거짓으로 좋아진다. 임계를 넘으면 눈에 띄게 표시된다.
  thresholds: {
    http_req_failed: ['rate<0.001'],
  },
  discardResponseBodies: false,   // cached 플래그를 읽어야 한다
};

// ★ CDF 를 SharedArray 로 VU 간 1회만 구축한다. VU 마다 만들면 부하 생성기가 죽는다.
const CDF = new SharedArray('cdf', () => [buildCdf(N, SKEW)]);
// VU 마다 자기 시드 → 재현 가능. 전체 합은 Zipf.
const sampler = samplerFromCdf(CDF[0], N, SEED + __VU);

export default function () {
  const key = keyName(sampler());
  const res = http.get(`${TARGET}/key/${key}`, { tags: { name: 'key' } });

  if (res.status === 200) {
    const body = res.json();
    cacheHit.add(body.cached);
    nodeHits.add(1, { node: body.nodeId });
  }
}
