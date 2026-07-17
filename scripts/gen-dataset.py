#!/usr/bin/env python3
"""데이터셋 생성기 — 키 공간 N 개의 CSV.

키 이름은 k6/zipf.js 의 keyName() 과 **정확히 일치해야 한다**.
어긋나면 전부 404 → 미스 100% 가 되는데, 그건 "캐시가 안 듣는다"처럼 보인다.
"""
import sys, pathlib

def main():
    if len(sys.argv) != 3:
        sys.exit("사용법: gen-dataset.py <N> <출력경로>")
    n, out = int(sys.argv[1]), pathlib.Path(sys.argv[2])
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w") as f:
        f.write("id,value\n")
        for i in range(n):
            # value 는 의미 없다 — 미스 시 origin 이 뭔가 돌려주기만 하면 된다.
            f.write(f"key-{i},{i}\n")
    print(f"{out}: {n:,} 행")

if __name__ == "__main__":
    main()
