function R = rppg_hrv(x, fs, hrHintBpm)
%RPPG_HRV 협대역 맥파 → 박동 → RR → HRV·스트레스 (앱 Hrv.kt와 동일)
%   x 는 심박 ±0.6 Hz 대역통과된 맥파여야 함 (rppg_analyze 참고)
%   박동: 상향 영교차(선형 보간), 최소 간격 = max(0.33 s, 0.6 × 심박 주기)
%   RR 정제: 0.33–1.5 s, 중앙값 ±25% 밖 제거
%   Baevsky SI = AMo / (2·Mo·MxDMn), 50 ms 빈, MxDMn은 5–95 백분위 범위
%   점수 = (√SI − 5)/15 × 100 (0–100), 등급 √SI <7 낮음 / <12 보통 / <18 다소 높음 / 그 이상 높음
%   신뢰도: 기대 박동 대비 정상 RR 비율 ≥ 0.85 일 때만 reliable
%   실패(데이터 부족) 시 [] 반환
x = x(:); n = numel(x); R = [];
if n < 30*fs, return; end
hint = min(max(hrHintBpm, 42), 180);
minDist = max(0.33, 0.6*60/hint) * fs;

pk = [];
for i = 2:n
    a = x(i-1); b = x(i);
    if a < 0 && b >= 0
        p = (i - 2) + (-a)/(b - a);          % 0부터 세는 샘플 위치 (앱과 동일)
        if ~isempty(pk) && p - pk(end) < minDist, continue; end
        pk(end+1) = p; %#ok<AGROW>
    end
end
if numel(pk) < 16, return; end

rr = diff(pk(:)) / fs;
rr = rr(rr >= 0.33 & rr <= 1.5);
if numel(rr) < 15, return; end
s = sort(rr); med = s(floor(numel(s)/2) + 1);
rr = rr(abs(rr - med) <= 0.25*med);
if numel(rr) < 15, return; end

mu = mean(rr);
sdnn = std(rr);                                % n−1
rmssd = sqrt(mean(diff(rr).^2));

bin = 0.05;
k = floor(rr / bin);
[uk, ~, ic] = unique(k);
cnt = accumarray(ic, 1);
[cmax, j] = max(cnt);
Mo = (uk(j) + 0.5) * bin;
AMo = cmax * 100 / numel(rr);
s = sort(rr); N = numel(s);
p5 = s(round(0.05*(N-1)) + 1);
p95 = s(round(0.95*(N-1)) + 1);
MxDMn = max(p95 - p5, 0.02);
si = sqrt(AMo / (2*Mo*MxDMn));

score = min(max(round((si - 5)/15*100), 0), 100);
if si < 7,      level = '낮음 (이완)';
elseif si < 12, level = '보통';
elseif si < 18, level = '다소 높음';
else,           level = '높음';
end
dur = n / fs;
yld = min(numel(rr) / (dur*hint/60), 1);

R = struct('meanHr', 60/mu, 'sdnnMs', sdnn*1000, 'rmssdMs', rmssd*1000, ...
    'sqrtSI', si, 'Mo', Mo, 'AMo', AMo, 'MxDMn', MxDMn, ...
    'score', score, 'level', level, 'beats', numel(rr) + 1, 'durationSec', dur, ...
    'beatYield', yld, 'reliable', yld >= 0.85, ...
    'beatTimes', pk(:)/fs, 'rr', rr);
end
