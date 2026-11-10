# 말노트 (MalNote)

갤럭시 Z 폴드 6 용 **온디바이스 한국어 음성 녹음·전사 앱**입니다. 다글로처럼 녹음하면 바로 텍스트로 받아써 주며,
OpenAI **Whisper large-v3** 계열 오픈소스 모델을 [whisper.cpp](https://github.com/ggml-org/whisper.cpp) 로
**휴대폰 안에서(오프라인)** 실행합니다. 음성이 서버로 전송되지 않습니다.

## 기능

- 🎙️ 녹음 (16kHz WAV, 화면을 꺼도 계속 녹음 · 일시정지/계속 · 실시간 파형)
- 📝 녹음이 끝나면 자동 전사 — 문장 단위 타임스탬프, 전사 중에도 문장이 실시간으로 나타남
- 📂 기존 오디오/동영상 파일 가져오기 (m4a, mp3, aac, wav, flac, ogg, mp4 등)
- ▶️ 재생하면서 현재 문장 하이라이트, 문장을 누르면 해당 위치로 이동
- 🔍 제목·내용 전체 검색, 이름 변경, 삭제
- 📤 텍스트 복사/공유, 타임스탬프 포함 공유, SRT 자막 내보내기, 오디오 공유
- 📱 폴드 펼침 화면에서는 목록 + 상세 2단 화면
- ⚙️ 모델 선택/다운로드, 직접 가져온 ggml 모델(.bin) 사용, CPU 스레드·빔 서치·초기 프롬프트 설정

## 모델

설정 화면에서 내려받습니다 (Hugging Face `ggerganov/whisper.cpp`, 앱 전용 저장소에 저장).

| 모델 | 크기 | 비고 |
|---|---|---|
| **Large-v3 Turbo Q5_0** | 약 550 MB | **추천.** large-v3 인코더 + 경량 디코더, 한국어 품질 좋고 빠름 |
| Large-v3 Turbo Q8_0 | 약 830 MB | 조금 더 정확 |
| Large-v3 Q5_0 | 약 1 GB | 원본 large-v3, 가장 정확하지만 느림 |
| Large-v3 FP16 | 약 3 GB | 양자화 없음 |
| Medium Q5_0 / Small Q5_1 | 500 / 180 MB | 빠른 테스트용 |

한국어로 파인튜닝된 Whisper 모델은 whisper.cpp 의 `models/convert-h5-to-ggml.py` 로 ggml 형식으로 변환한 뒤
설정 → "모델 파일(.bin) 직접 가져오기" 로 쓸 수 있습니다.

## 설치 (APK)

1. GitHub → **Actions → Android APK** 에서 최신 실행의 `malnote-apk` 아티팩트를 받습니다.
2. 폴드 6 에서 APK 를 열어 설치합니다 (출처를 알 수 없는 앱 설치 허용 필요).
3. 앱 → 설정 → **Large-v3 Turbo (Q5_0)** 받기 → 녹음 버튼.

## 직접 빌드

필요: JDK 17, Android SDK 35, NDK `27.2.12479018`, CMake `3.22.1`

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

whisper.cpp 는 빌드 중 CMake `FetchContent` 로 v1.9.5 커밋을 받아옵니다 (`app/src/main/cpp/CMakeLists.txt`).
네이티브 코드는 Snapdragon 8 Gen 3 에 맞춰 `armv8.2-a+dotprod+fp16+i8mm` 로 최적화되어 있어
i8mm 를 지원하지 않는 오래된 기기에서는 실행되지 않을 수 있습니다 (`GGML_CPU_ARM_ARCH` 값을 바꾸면 됩니다).

## 구조

```
app/src/main/
├── cpp/                 whisper.cpp JNI 브리지 (jni.cpp) + CMake
└── java/com/dku/malnote/
    ├── whisper/         WhisperLib / WhisperContext (JNI 래퍼)
    ├── audio/           WAV 기록, MediaCodec 디코딩 + 16kHz 리샘플링
    ├── data/            녹음 저장소(JSON), 모델 다운로드 관리, 설정
    ├── service/         RecordingService(마이크 FGS), TranscriptionService(전사 FGS, 대기열)
    └── ui/              Jetpack Compose 화면 (목록, 녹음, 상세/재생, 설정)
```

## 참고

- 전사 속도는 기기 발열·모델에 따라 다릅니다. Turbo Q5 기준 대략 녹음 길이의 1/3~1/2 정도 시간이 걸리는 것을 목표로 하며,
  원본 large-v3 는 그보다 몇 배 느립니다. 긴 녹음은 화면을 꺼 두어도 백그라운드에서 계속 전사됩니다.
- 화자 분리, 요약 기능은 아직 없습니다.
