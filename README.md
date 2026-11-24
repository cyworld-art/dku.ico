# rPPG 심박 측정 앱 (Android)

전면 카메라로 얼굴 피부색의 미세한 변화를 분석해 심박수(BPM)를 추정하는 앱입니다.

## 빌드 방법
1. Android Studio(Koala 이상)에서 `RppgApp` 폴더를 **Open**
2. Gradle Sync (Gradle 8.7 / AGP 8.5.2 / Kotlin 1.9.24 / JDK 17 자동 다운로드)
   - `gradlew` 스크립트가 필요하면 터미널에서 `gradle wrapper` 한 번 실행
3. 실기기 연결 후 Run (minSdk 24, 에뮬레이터는 카메라 품질 때문에 비권장)

## 처리 파이프라인
| 단계 | 파일 | 내용 |
|---|---|---|
| 카메라 | `MainActivity.kt` | CameraX 640×480 YUV, 30fps 요청, 측정 중 AE/AWB 잠금 |
| 얼굴·ROI | `RppgAnalyzer.kt` | ML Kit 얼굴 검출 → EMA 평활 → 이마·양 볼 ROI 평균 RGB |
| 신호 처리 | `RppgProcessor.kt` | 10초 버퍼 → 30Hz 재샘플링 → **POS** → 0.7–3.0Hz 버터워스 filtfilt → Hann+DFT 피크 → BPM·SNR |
| 표시 | `Views.kt` | ROI 오버레이, 실시간 맥파 파형 |

- 최초 결과까지 약 5초, 이후 0.5초마다 갱신, 최근 5개 추정치 중앙값 표시
- SNR(de Haan 방식): ≥3dB 좋음 / ≥0dB 보통 / 그 외 낮음

## 측정 팁
- 밝고 균일한 조명(창가 자연광 또는 정면 조명), 깜빡이는 형광등 피하기
- 얼굴을 가이드 타원 안에, 30–50cm 거리, 말하거나 움직이지 않기

## 확장 아이디어
- 원시 RGB/타임스탬프 CSV 저장(연구용 데이터 수집)
- 후면 카메라 + 플래시 손가락 접촉식 PPG 모드
- CHROM/ICA 등 다른 알고리즘 선택, HRV(RR 간격) 추정

> ⚠️ 연구·참고용이며 의료기기가 아닙니다.
