rootProject.name = "cache-split-lab"

// app    — SUT 노드. 세 arm 이 이 하나의 산출물을 공유한다.
//          arm 마다 코드를 복사하면 "arm 간 차이는 배치뿐" 불변식이 깨진다.
// origin  — 백킹 스토어. CSV 를 메모리에 올려두고 고정 지연 후 반환.
include("app", "origin")
