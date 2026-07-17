// Zipf 생성기 검정 — 실제 부하 스크립트가 쓰는 바로 그 코드 경로를 검사한다.
// 실행: k6 run --vus 1 --iterations 1 k6/verify-zipf.js
//
// "Zipf 아닌 Zipf 생성기"는 겉으로 구분이 안 된다. 그럴듯하고 틀린 히트율을 만든다.
import { zipfSampler, rng } from './zipf.js';

// 이론값: 상위 k 개가 먹는 트래픽 몫 = H(k,s) / H(n,s)
function theoryTopShare(n, s, k) {
  let hk = 0, hn = 0;
  for (let i = 1; i <= n; i++) { const t = 1 / Math.pow(i, s); if (i <= k) hk += t; hn += t; }
  return hk / hn;
}

export default function () {
  const N = 1000, SAMPLES = 2_000_000;
  console.log(`=== Zipf 생성기 검정 (N=${N}, 표본=${SAMPLES.toLocaleString()}) ===`);
  console.log('   s  | 상위10 실측 | 상위10 이론 |   오차   | 판정');
  console.log('------+-------------+-------------+----------+------');

  let allPass = true;
  for (const s of [0, 0.5, 1.0, 1.5, 2.0]) {
    const next = zipfSampler(N, s, 42);
    let top10 = 0;
    for (let i = 0; i < SAMPLES; i++) if (next() < 10) top10++;
    const actual = top10 / SAMPLES;
    const theory = theoryTopShare(N, s, 10);
    const err = Math.abs(actual - theory);
    const pass = err < 0.005;                       // 표본오차 여유
    if (!pass) allPass = false;
    console.log(
      `  ${s.toFixed(1)} |   ${(actual * 100).toFixed(2).padStart(6)}%   |   ` +
      `${(theory * 100).toFixed(2).padStart(6)}%   |  ${(err * 100).toFixed(3)}%p  | ${pass ? 'PASS' : 'FAIL'}`
    );
  }

  // 재현성: 같은 시드 → 같은 시퀀스 (arm 간 비교 가능성의 전제)
  const a = zipfSampler(N, 1.0, 7), b = zipfSampler(N, 1.0, 7), c = zipfSampler(N, 1.0, 8);
  let same = true, diff = false;
  for (let i = 0; i < 10000; i++) { const x = a(); if (x !== b()) same = false; if (x !== c()) diff = true; }
  console.log(`\n  같은 시드 → 같은 시퀀스 : ${same ? 'PASS' : 'FAIL'}`);
  console.log(`  다른 시드 → 다른 시퀀스 : ${diff ? 'PASS' : 'FAIL'}`);

  // 균등(s=0) 이 정말 균등한가 — 카이제곱 대신 최대 편차로 간단 검정
  const u = zipfSampler(100, 0, 3); const bins = new Array(100).fill(0);
  for (let i = 0; i < 1_000_000; i++) bins[u()]++;
  const expect = 10000, maxDev = Math.max(...bins.map(b => Math.abs(b - expect) / expect));
  console.log(`  s=0 균등성 (최대 편차)  : ${(maxDev * 100).toFixed(2)}% ${maxDev < 0.05 ? 'PASS' : 'FAIL'}`);

  if (!allPass) throw new Error('Zipf 생성기 검정 실패');
}
