#!/usr/bin/env bash
# BPC(SUT)와 APC(부하)에 배포한다.
#
# 세 arm 이 **같은 jar** 를 쓴다 — 여기서 한 번 빌드해서 보내고, BPC 는 그걸 담기만 한다.
# 컨테이너 안에서 Gradle 을 돌리면 arm 을 바꿔 띄울 때마다 재빌드가 일어나
# "세 arm 이 같은 산출물을 쓴다"를 보장하기 어려워진다.
set -euo pipefail

BPC=${BPC:-jun@192.168.55.164}
APC=${APC:-jun@192.168.55.9}
REMOTE_DIR=${REMOTE_DIR:-cache-split-lab}
cd "$(dirname "$0")/.."

echo "══ jar 빌드 (셋이 공유할 단일 산출물) ══"
./gradlew build -q
ls -la app/build/libs/app.jar origin/build/libs/origin.jar

echo
echo "══ BPC($BPC) 배포 ══"
rsync -az --delete \
  --include='app/'            --include='app/Dockerfile' \
  --include='app/build/'      --include='app/build/libs/'      --include='app/build/libs/app.jar' \
  --include='origin/'         --include='origin/Dockerfile' \
  --include='origin/build/'   --include='origin/build/libs/'   --include='origin/build/libs/origin.jar' \
  --include='nginx/***' --include='arms/***' --include='data/***' \
  --include='results/' \
  --exclude='*' \
  ./ "$BPC:$REMOTE_DIR/"
ssh "$BPC" "ls $REMOTE_DIR/app/build/libs/ $REMOTE_DIR/data/ | head -12"

echo
echo "══ APC($APC) 배포 (k6 스크립트) ══"
rsync -az --delete ./k6/ "$APC:$REMOTE_DIR-k6/"
ssh "$APC" "ls $REMOTE_DIR-k6/"

echo
echo "✅ 배포 완료"
