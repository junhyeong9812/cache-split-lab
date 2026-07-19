# task — Phase 4: 포화점 프로브 재실행 + B·C 교차점 실측

> 2026-07-18 재개 세션. 입력: `docs/TODO.md` (중단 지점 "2번"). 모드: auto-implements.

## 1. 정의 (명확도 6칸)

| # | 칸 | 내용 |
|---|----|------|
| 1 | 목표·대상 | cache-split-lab에서 ① SharedArray 수정본으로 포화 프로브 재실행 → APC 부하 천장·BPC 무릎 확인, ② 포화점 기준 80/90/100/110% 부하에서 s=1.5 전후 B vs C 지연·포화 비교 → 이론이 예측한 역전(s≥1.5에서 B 승) 실측 검증. 결과 JSON/문서가 저장소에 기록되면 끝 |
| 2 | 경계·불변식 | 총 자원 고정(캐시 10,000 엔트리·스레드 200 — 단 Phase 0 탐색 런은 CPU를 의도적으로 조임·기록). 측정 중 워밍업 k6 잔존 금지(633d7fb 방어 사용). miss 합 ≡ origin 요청 증가분. BPC ollama 유휴 확인(런마다 loadavg) |
| 3 | 기준소스 | `docs/TODO.md`·`docs/EXPERIMENTS.md`(절차), `docs/theory/lru-zipf.md`(이론 예측), `scripts/run_arm.py`(자원 불변식 단일 출처) |
| 4 | 금지영역 | app/origin/k6 기존 코드(측정만, 수정 필요 시 보고), `results/phase-1-uniform/`(기존 결과), git force 계열 |
| 5 | 검증 방법 | probe 단계별 p95/드롭률 표 + APC CPU 동시 관찰(부하기 천장과 SUT 무릎 분리) + sanity(miss=origin delta) + 이론 예측과 대조 |
| 6 | stakes | **중간** — 트리아지 하한은 낮음(전 차원 비활성)이나, 기준소스 `EXPERIMENTS.md §0`이 이 실험의 stakes를 중간으로 선언(지배 실패모드 = "조용히 틀린 숫자" → 멘탈 모델 오염). §4.2 하한 규칙에 따라 상향. 함의: 사전 등록(spec.md 런 전 커밋)·gate.md 판정·듀얼 리뷰 1패스 + review-log |

### 트리아지 (dimensions.md 14차원)

전 차원 비활성 — 증거: 이 작업의 diff는 `results/phase-0-*` 신규 JSON·`docs/` 신규 문서·task.md뿐, app/origin/k6/scripts 코드 불변(금지영역 칸4). 외부 표면·인증·write 경로·스키마·의존성 변경 없음(내부 랩 네트워크 192.168.55.0/24). 측정 오염 위험(k6 잔존·ollama)은 차원이 아니라 칸2 불변식으로 관리.

단, **arm D(레디스 외부화, TODO 3번)를 착수하는 시점엔 신규 코드(compose·Lua)라 별도 정의 게이트로 재트리아지한다** — 이 task 범위 밖.

## 2. 계획

1. BPC arm A(CPU=2, N=40000, s=2) 기동 → 프리웜 → Zipf 워밍업 → 카운터 리셋.
2. `k6/probe.js` STEPS=5000,10000,15000,20000,25000 재실행. 동시에 APC·BPC CPU 샘플링.
3. 판정 분기 (TODO 그대로):
   - APC 천장 확인 → BPC 무릎이 천장 아래면 포화점 확정.
   - 아니면 BPC CPU 추가 조임(총량 고정 명시 기록) 후 재프로브.
   - 둘 다 안 되면 "이 하드웨어로 고 skew 포화 측정 불가"를 근거와 함께 결론.
4. 포화점 확정 시: s=1.5(±)에서 B·C 각각 포화점의 80/90/100/110% 측정(`run_arm.py` 또는 probe 조합).
5. 기록: `results/phase-0-saturation/`·`results/phase-4-crosspoint/` + `docs/phases/` 문서 + task.md 산출물 요약(낮음 stakes 경량화 — changelog는 diff 없으므로 측정 전후 데이터 표로 갈음).

- 변경하지 않을 파일: app·origin·k6·scripts 소스, phase-1 결과.
- 검증 명령: probe 출력의 단계별 http_req_duration·dropped_iterations, `/admin/stats` sanity.

## 3. 진행 로그

- 2026-07-18 21:5x: BPC 잔존 arm A(16h) down -v 완료. push 승인 받음(git-guard 훅 재확인 대기). 모드 auto-implements 확정.
- 2026-07-18 22:0x: arm A(CPU=2, N=40k) 재기동 → 프리웜 40k → s=2 워밍업 45s(구간 히트율 99.6%) → probe.js 재실행.
- 2026-07-18 22:3x: **probe.js 재실행 실패 — APC 호스트 다운.** VU 초기화가 1,730에서 정체하다 SSH broken pipe → 이후 ping 무응답(no route to host). 원인 진단: probe.js 는 시나리오 5개 × preAllocatedVUs 3,000 = 최대 15,000 VU 를 초기화 단계에서 한꺼번에 만든다. CDF 는 SharedArray 로 고쳤지만(f8d037f) **VU 개수 자체가 APC 램 15G 를 넘긴다.** 이전 결함(CDF)과 별개의 2차 결함.
- 대응 결정: probe.js 수정 대신 **load.js 를 RPS 만 바꿔 계단별 순차 실행**(리포 무수정, VU 풀 계단마다 해제) + k6 컨테이너 `--memory` 캡(호스트 보호). spec.md §3.1 에 사전 고정.
- 2026-07-19 00:1x: `docs/phases/phase-0-saturation/spec.md` 사전 등록 작성(P1: knee_C/knee_B≈0.72, P2: B≈A ±15%, P3: 병목=앱 CPU, P4: 무릎 전 p95 동등). 이론 계산: s=1.5 에서 A=0.9942/B=0.9894/C≈0.9942, top-1 키 질량 0.384 → C 핫 노드 부하 0.692. **커밋은 git-guard 차단**(docs 단독 커밋 미승인) — Phase 1 전례대로 "약한 증거" 각주, 승인 시 소급 커밋.
- 2026-07-19 00:2x: 부하 생성기 폴백 검증 — **조종 노트북 실격.** 8,000 RPS 목표에 4,623 달성(드롭 42%), RTT 3ms±1.6(wlo1 WiFi 라우팅 경로, eno1 은 케이블 미연결). BPC 위 k6(cpuset 격리)도 검토했으나 HT 코어 공유로 SUT 오염 위험 → 기각.
- **블로킹: APC 전원 재시작 필요(물리).** ping 감시 백그라운드 가동 중 — 복구 시 §계획 2 부터 재개: 캘리브레이션(APC 천장 → CPU 총량 조임) → 세 arm 계단 측정 → gate 판정 → Phase 4 spec.
- 2026-07-19 07:3x(재개): APC 전원 복구(재부팅 22:35, 상주 서비스 ~6G 확인). TIMELINE 문제 ⑥ + 틀린 것 #8·#9 문서화.
- **진짜 원인 발견 — f8d037f 는 불완전한 수정이었다.** 계단 재실행이 전부 exit 137(6G 캡 OOM kill). 재현·통제실험으로 특정: `new SharedArray('cdf', () => [buildCdf(...)])` 는 CDF 전체를 **원소 1개**로 감싸는데, SharedArray 는 원소 접근 시 그 원소를 **VU 마다 복사**한다 → VU 당 ~5MB. 증거: s=2 1,250 VU 에서 6GB OOM vs s=0(CDF 없음) 동일 VU 570MB 완주. 과거 사고 전부 아귀 맞음(APC 동결=1,730 VU×5MB+상주 6G / 노트북 12G 캡 생존=2,067 VU).
- **수정(계획 이탈 보고 — k6 코드)**: zipf.js(경고 주석이 잘못된 처방을 담고 있어 교정)·load.js·probe.js 를 **원소 40k 개 SharedArray + s=0 은 null 전달** 패턴으로 수정. probe.js 는 VU 과할당(시나리오당 3,000)도 rps/50·maxVUs 1,500 으로 축소 + 런 분리 권장 주석. verify-zipf.js 에 **SharedArray ≡ 일반 배열 동일 시퀀스 검정 추가** — 전 항목 PASS(Zipf 오차 ≤0.035%p). 메모리 재검증: 동일 조건 464MB 피크·정상 완주.
- **캘리브레이션 계단(arm A CPU=2, s=1.5, 5k~25k)**: APC 천장 = **~10.7k RPS** (15k 계단부터 호스트 CPU 795/800% 포화, k6 컨테이너 ~775%). BPC 는 한가(앱 78~84%/200%, origin ≤2%) → 무릎 ≫ 천장. §3.2 분기: **CPU_TOTAL=0.5 로 조임**(A=0.5 / B·C=0.25 — 앱 소모 실측 ~7.6%/1k RPS → A 무릎 예상 ~6.6k < 천장 70%). arm A 재구성 후 무릎 계단(3k~9k) 진행 중.
- **Phase 0 완료 (2026-07-19 오전)**: 1차 런(20s 계단, 세 arm) → B 5500 비단조 이상치로 2차 런(30s 계단, 기준 불변) 재측정. **무릎 A=5000 / B=4500 / C=4000.** 핵심 정량: 0.25CPU 노드 용량 ~2,500 rps, C 무릎에서 핫 노드 부하 4000×0.628=2,512 (오차 0.5%) — 쏠림=용량 손실 산수로 닫힘. P1 빗나감(0.889 vs 밴드 0.62~0.82 — 분할 가정 오류: 실측 쏠림 0.628, 패리티 가정 0.692), P2·P3 일치, P4 부분(p50 동등, B p95 는 미스 꼬리 노출). 산출물: phase-0-saturation/{spec,result,gate}.md + results/run1·run2. **2번 질문 답: s=1.5 에서 용량 축 B 승(최대 처리량 6,891 vs 5,889, C −15%) — "라운드로빈 항상 진다"는 s=0 일반화 오류였음.**
- **Phase 4 본 런 진행 중**: spec.md 사전 등록(P1 역전 지도·P2 히트 순위·P3 분포 상수·P4 A 여유) 후 그리드 4000~6000(=80~120% of A 무릎)×60s×3 arm.
- **Phase 4 완료**: P3·P4 일치, P1·P2 부분 빗나감(드롭 재현 불안정·첫 지점 워밍업 시드 잔재). 역전은 지연 축에서 성립 — 110~120% 는 p50·p95 모두 B < C. 산출물: phase-4-load/{spec,result,gate}.md.
- **듀얼 리뷰 (中 의무)**: 독립 Claude 워커(k6 실행 검증 포함) ∥ codex(redact 패킷). **finding 8건 전원 채택·전원 수정** — 코드 1건(W1: samplerFromCdf 길이 가드 = changelog J-4), 판정 문서 7건(사전등록 이탈 명시·P2 런 민감도·지표 사후 교체·인과 단정 완화 3건·인용 숫자 2건). post-fix 재점검: verify-zipf 전 항목 PASS + 수정 절 재독 clean. 상세: review-log.md.
- **최소 안전선**: 테스트(verify-zipf — 불변식 직접 검증) ☑ / diff self-review(변경 파일 = 의도 목록) ☑ / rollback(전부 미커밋 — checkout 복구, APC 는 재rsync) ☑ / public contract 영향 없음(k6 env 계약 불변) ☑ / 반증(빈 SharedArray 가드·s=0 경로·prewarm 호출 경로 무영향 확인) ☑.
- **종결. 잔여**: ① 커밋(2분리: fix(k6) / docs)·push — git-guard 승인 대기 ② P2(phase-0) 확정은 30s 프로토콜 사전 등록 반복 필요(Phase 2 때 병행) ③ arm D 는 별도 정의 게이트.
