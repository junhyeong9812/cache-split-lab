// ─────────────────────────────────────────────────────────────────────────
// Zipf 키 생성기 — 이 실험의 핵심 축.
//
// 빈도 ∝ 1 / 순위^s  (docs/GLOSSARY.md §4)
//   s = 0   완전 평평 (균등)      → 중복 캐시 = 낭비
//   s = 1   고전적 Zipf           → 평상시 이커머스
//   s = 2   극단적 쏠림           → 플래시 세일 → 중복 캐시 = 기능
//
// ★ 이 파일이 틀리면 모든 숫자가 틀린다. 그리고 "Zipf 아닌 Zipf"는 겉으로
//   구분이 안 된다 — 그래서 verify-zipf.js 가 이 코드 경로를 직접 검정한다.
// ─────────────────────────────────────────────────────────────────────────

/**
 * mulberry32 — 시드 가능한 PRNG.
 * Math.random() 은 시드를 못 줘서 재현이 안 된다.
 * arm A/B/C 에 통계적으로 동일한 스트림을 먹이려면 시드가 필수다.
 */
export function rng(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/**
 * 누적분포(CDF)를 미리 만들어 두고 이분탐색으로 뽑는다.
 * N=80,000 이면 Float64Array 640KB, 탐색 17스텝 — 부하 생성기가 병목이 되지 않는다.
 *
 * s = 0 은 CDF 가 필요 없다(균등) — 특수 처리해서 80,000번 계산을 통째로 건너뛴다.
 */
export function buildCdf(n, s) {
  // cdf[i] = (1/1^s + ... + 1/(i+1)^s) / H(n,s).  균등(s=0)이면 null 반환(CDF 불필요).
  if (s === 0) return null;
  const cdf = new Array(n);
  let acc = 0;
  for (let i = 0; i < n; i++) { acc += 1 / Math.pow(i + 1, s); cdf[i] = acc; }
  for (let i = 0; i < n; i++) cdf[i] /= acc;
  return cdf;
}

// ⚠️ CDF 를 VU 마다 갖게 하면 부하 생성기가 먼저 죽는다. 두 번 데였다:
//    ① buildCdf() 를 VU 안에서 직접 호출 — VU 마다 새로 계산 (2026-07-18:
//       3,884 VU 에서 드롭 86만·에러 13%·p95 1.79s).
//    ② `new SharedArray('cdf', () => [buildCdf(...)])` 처럼 **전체 배열을 원소
//       1개로 감싸기** — SharedArray 는 "원소 접근 시 그 원소를 복사"하므로
//       CDF[0] 을 읽는 순간 VU 마다 40k 배열 전체 사본(~5MB)이 생긴다. VU 1,250
//       개 × 5MB = 6GB (2026-07-19 실측: 컨테이너 OOM kill. 전날 APC 호스트
//       동결도 같은 원인 — TIMELINE.md 문제 ⑥).
//    올바른 패턴: **원소 40k 개 그대로** 담아 접근당 스칼라 하나만 복사되게 한다.
//        const CDF = new SharedArray('cdf', () => buildCdf(N, SKEW) || []);
//        const sampler = samplerFromCdf(SKEW === 0 ? null : CDF, N, seed);
//    (s=0 은 buildCdf 가 null → 빈 배열로 채우고 null 을 넘긴다. 이분탐색은
//    요청당 ~17회 스칼라 접근 — SharedArray 역직렬화 비용은 무시 가능.)
export function samplerFromCdf(cdf, n, seed) {
  const rand = rng(seed);
  if (cdf === null) return () => Math.floor(rand() * n);   // 균등
  // 빈 SharedArray(s=0 용)가 cdf 로 잘못 들어오면 이분탐색이 조용히 전부
  // 인덱스 0 으로 수렴한다 — 에러 없이 그럴듯한 숫자가 나오는 최악 모드. 즉시 죽인다.
  if (cdf.length !== n) throw new Error(`CDF 길이 ${cdf.length} ≠ N ${n} — s=0 은 null 을 넘겨라`);
  return () => {
    const u = rand();
    let lo = 0, hi = n - 1;
    while (lo < hi) { const mid = (lo + hi) >>> 1; if (cdf[mid] < u) lo = mid + 1; else hi = mid; }
    return lo;
  };
}

// 하위호환 — 단발성 스크립트(verify-zipf 등)용. 부하 스크립트에서는 쓰지 마라.
export function zipfSampler(n, s, seed) {
  return samplerFromCdf(buildCdf(n, s), n, seed);
}

/**
 * 키 이름. 데이터셋 CSV 의 id 와 정확히 일치해야 한다.
 * scripts/gen-dataset.py 와 이 함수가 어긋나면 전부 404 → 미스 100% 로 보인다.
 */
export function keyName(index) {
  return `key-${index}`;
}
