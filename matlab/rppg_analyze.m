%% rPPG 오프라인 분석 — 안드로이드 앱과 동일한 알고리즘
%  1) 앱: 측정 → 결과 창의 [저장] → 휴대폰 '다운로드/rPPG' 폴더에 3개 파일 생성
%        rppg_YYYYMMDD_HHMMSS_raw.csv / _hr.csv / _summary.txt
%  2) PC로 복사 후 이 스크립트 실행 → raw.csv 선택 (취소하면 합성 데이터로 데모)
%  필요: MATLAB R2020a 이상 (툴박스 불필요)
clear; close all; clc;
fs = 30;                                    % 앱의 재샘플링 주파수 [Hz]

%% 1. 데이터 불러오기
[fn, fp] = uigetfile('*_raw.csv', '앱에서 저장한 *_raw.csv 선택 (취소 = 합성 데이터)');
if isequal(fn, 0)
    [t, wall, rgb, face, truth] = rppg_synthetic(72, 60, fs, 0.1);
    rpApp = zeros(0,2); watch = zeros(0,2); tol = 3; base = 'synthetic';
    fprintf('합성 데이터: 실제 HR %.1f BPM, SDNN %.0f ms, RMSSD %.0f ms\n', ...
        truth.hrBpm, truth.sdnnMs, truth.rmssdMs);
else
    rawFile = fullfile(fp, fn);
    base = erase(rawFile, '_raw.csv');
    [t, wall, rgb, face] = rppg_load_raw(rawFile);
    [rpApp, watch] = rppg_load_hr([base '_hr.csv']);
    tol = 3;
    sumFile = [base '_summary.txt'];
    if isfile(sumFile)
        txt = fileread(sumFile);
        if contains(txt, 'watch_mode=hc'), tol = 30; end
        fprintf('---- 앱 요약 (%s) ----\n%s\n', sumFile, txt);
    end
    truth = [];
end
fprintf('프레임 %d개, %.1f초, 평균 %.1f fps, 얼굴 검출 %.0f%%\n', numel(t), t(end)-t(1), ...
    (numel(t)-1)/(t(end)-t(1)), 100*mean(face));

%% 2. 분석 구간: 얼굴이 계속 보인 가장 긴 연속 구간 (앱 버퍼와 동일한 규칙)
idx = rppg_longest_segment(t, face, 0.5);
ts = t(idx); X = rgb(idx, :); ws = wall(idx);
fprintf('분석 구간 %.1f초 (프레임 %d개)\n', ts(end)-ts(1), numel(idx));
if ts(end) - ts(1) < 5, error('rppg:short', '연속 구간이 5초 미만입니다.'); end

%% 3. 전체 구간 맥파: 재샘플링 → POS → 0.7–3.0 Hz
[tu, Y] = rppg_resample(ts, X, fs);
H = rppg_pos(Y(:,1), Y(:,2), Y(:,3), fs);
bvp = rppg_bandpass(H, fs, 0.7, 3.0);
[f0, snrAll, fgrid, P] = rppg_spectral_peak(bvp, fs, 0.7, 3.0);
fprintf('전체 구간 스펙트럼 심박 %.1f BPM (SNR %.1f dB)\n', f0*60, snrAll);

%% 4. 실시간 심박 추적 (앱 화면 표시값 재현)
T = rppg_hr_track(ts, X, fs);
trkWall = interp1(ts, ws, ts(1) + T.t);      % 센서 시간 → 측정 시작 기준 시간(워치와 같은 축)
fprintf('추적 심박 중앙값 %.1f BPM\n', median(T.shown, 'omitnan'));

%% 5. HRV·스트레스: 심박 ±0.6 Hz 협대역 + 영교차 박동
hint = median(T.shown, 'omitnan'); if isnan(hint), hint = f0*60; end
fc = hint/60;
bvpN = rppg_bandpass(H, fs, max(0.6, fc - 0.6), fc + 0.6);
R = rppg_hrv(bvpN, fs, hint);
if isempty(R)
    fprintf('스트레스: 데이터 부족 (연속 30초 이상, 박동 15개 이상 필요)\n');
else
    rel = '신뢰'; if ~R.reliable, rel = '신호 불충분 → 앱에서는 표시 안 함'; end
    fprintf('스트레스 %d/100 (%s) | √SI %.1f | RMSSD %.0f ms | SDNN %.0f ms | 박동 검출률 %.0f%% (%s)\n', ...
        R.score, R.level, R.sqrtSI, R.rmssdMs, R.sdnnMs, 100*R.beatYield, rel);
end

%% 6. 워치 비교
Cm = []; Ca = [];
if ~isempty(watch)
    Cm = rppg_compare(trkWall, T.shown, watch(:,1), watch(:,2), tol);
    fprintf('워치 비교(MATLAB 재계산): MAE %.1f, 편향 %+.1f, RMSE %.1f BPM (n=%d)\n', Cm.mae, Cm.bias, Cm.rmse, Cm.n);
    if ~isempty(rpApp)
        Ca = rppg_compare(rpApp(:,1), rpApp(:,2), watch(:,1), watch(:,2), tol);
        fprintf('워치 비교(앱 표시값):      MAE %.1f, 편향 %+.1f, RMSE %.1f BPM (n=%d)\n', Ca.mae, Ca.bias, Ca.rmse, Ca.n);
    end
end

%% 7. 그림
figure('Name', 'rPPG 분석', 'Color', 'w', 'Position', [80 80 1100 800]);
tt = tu - tu(1);
subplot(4,1,1);
plot(tt, Y ./ mean(Y,1) - 1); grid on; legend('R','G','B'); ylabel('정규화 색 변화');
title('ROI 평균 RGB (재샘플링)');
subplot(4,1,2);
plot(tt, bvp, 'Color', [0.85 0.2 0.35]); hold on;
plot(tt, bvpN, 'Color', [0.2 0.5 0.9]);
if ~isempty(R)
    plot(R.beatTimes, interp1(tt, bvpN, R.beatTimes), 'k^', 'MarkerFaceColor', 'k', 'MarkerSize', 4);
end
grid on; ylabel('맥파'); legend('BVP 0.7–3 Hz', 'HRV용 협대역', '검출 박동');
title('POS 맥파');
subplot(4,1,3);
plot(fgrid*60, P/max(P), 'k'); hold on; xline(f0*60, 'r--', sprintf('%.1f BPM', f0*60));
xlim([30 240]); grid on; xlabel('BPM'); ylabel('정규화 파워'); title(sprintf('스펙트럼 (SNR %.1f dB)', snrAll));
subplot(4,1,4);
plot(trkWall, T.shown, 'Color', [0.85 0.2 0.35], 'LineWidth', 1.5); hold on;
lg = {'rPPG (MATLAB)'};
if ~isempty(rpApp), plot(rpApp(:,1), rpApp(:,2), ':', 'Color', [0.5 0.1 0.2]); lg{end+1} = 'rPPG (앱)'; end
if ~isempty(watch), plot(watch(:,1), watch(:,2), 'o-', 'Color', [0.2 0.6 0.9]); lg{end+1} = '워치'; end
grid on; xlabel('측정 시작 후 시간 [s]'); ylabel('BPM'); legend(lg); title('심박 추적 비교');

if ~isempty(R)
    figure('Name', 'HRV', 'Color', 'w', 'Position', [120 120 900 380]);
    subplot(1,2,1);
    plot(R.rr*1000, '.-'); grid on; xlabel('박동 번호'); ylabel('RR [ms]');
    title(sprintf('RR 간격 (RMSSD %.0f ms, SDNN %.0f ms)', R.rmssdMs, R.sdnnMs));
    subplot(1,2,2);
    edges = (floor(min(R.rr)/0.05):ceil(max(R.rr)/0.05)) * 0.05;
    if numel(edges) < 2, edges = [edges, edges + 0.05]; end
    histogram(R.rr, edges); grid on; xlabel('RR [s]'); ylabel('빈도');
    title(sprintf('Baevsky: Mo %.3f s, AMo %.0f%%, √SI %.1f → %d점', R.Mo, R.AMo, R.sqrtSI, R.score));
end

%% 8. 결과 저장 (.mat)
out = struct('base', base, 'fs', fs, 'segmentSec', ts(end)-ts(1), 'hrSpectralBpm', f0*60, ...
    'snrDb', snrAll, 'track', T, 'trackWall', trkWall, 'hrv', R, 'compareMatlab', Cm, 'compareApp', Ca, 'truth', truth);
matFile = [base '_matlab.mat'];
try
    save(matFile, '-struct', 'out');
    fprintf('결과 저장: %s\n', matFile);
catch
    fprintf('결과 .mat 저장 실패 (쓰기 권한 확인)\n');
end
