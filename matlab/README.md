# rPPG 알고리즘 — MATLAB 구현

안드로이드 앱(Kotlin)과 **같은 계수·초기화·반올림 규칙**으로 옮긴 MATLAB 코드입니다.
툴박스 없이 기본 MATLAB(R2020a 이상)으로 동작합니다.

## 빠른 시작
1. 앱: 메뉴(⋮) → 기본 설정 → "측정 완료 시 자동 저장" 켜기 (또는 결과 창의 [저장])
2. 휴대폰 `다운로드/rPPG` 폴더의 3개 파일을 PC로 복사
   - `rppg_YYYYMMDD_HHMMSS_raw.csv` — 프레임별 ROI 평균 RGB (`t_s, wall_s, R, G, B, face`)
   - `rppg_..._hr.csv` — 앱 표시 심박(source=1)·워치 심박(source=2) (`t_s, bpm, source`)
   - `rppg_..._summary.txt` — 앱 결과·프로필 요약 (key=value)
3. 이 폴더를 MATLAB 경로에 두고 `rppg_analyze` 실행 → `_raw.csv` 선택
   (취소하면 정답을 아는 합성 데이터로 데모 실행)

## 파일 ↔ 앱 코드 대응
| MATLAB | 앱(Kotlin) | 내용 |
|---|---|---|
| `rppg_analyze.m` | `MainActivity` | 전체 파이프라인, 그림, 결과 `.mat` 저장 |
| `rppg_longest_segment.m` | `RppgProcessor.add` | 얼굴 미검출/0.5초 끊김 시 버퍼 초기화 규칙 |
| `rppg_resample.m` | `resample` | 불규칙 프레임 → 30 Hz 선형 보간 |
| `rppg_pos.m` | `pos` | POS 맥파 추출 (1.6초 창) |
| `rppg_bandpass.m` | `Biquad`, `filtfilt` | 2차 버터워스 HP/LP, zero-phase |
| `rppg_spectral_peak.m` | `spectralPeak` | Hann + DFT, 포물선 보간, SNR |
| `rppg_hr_track.m` | `onFrame` 추정 루프 | 0.5초마다 10초 창, SNR>−3 dB 5개 중앙값 |
| `rppg_hrv.m` | `Hrv.analyze` | 영교차 박동, RR 정제, RMSSD/SDNN, Baevsky √SI, 점수·신뢰도 |
| `rppg_compare.m` | `compareStats` | 워치와 시점 매칭 MAE/편향/RMSE |
| `sasang_score.m` | `Constitution.score` | 사상체질 설문 채점 |
| `rppg_synthetic.m` | — | 검증용 합성 신호 (정답 HR·HRV 포함) |
| `rppg_load_raw.m`, `rppg_load_hr.m`, `rppg_read_csv.m` | `SessionStore` | 앱 저장 파일 읽기 |

## 참고 문헌
- W. Wang et al., "Algorithmic Principles of Remote PPG," IEEE TBME 64(7), 2017 (POS)
- G. de Haan, V. Jeanne, "Robust Pulse Rate From Chrominance-Based rPPG," IEEE TBME 60(10), 2013 (SNR 정의)
- R. M. Baevsky, A. G. Chernikova, "Heart rate variability analysis: physiological foundations and main methods," Cardiometry 10, 2017 (Stress Index)

> 연구·참고용이며 의료기기가 아닙니다.
