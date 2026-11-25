function [tu, Y] = rppg_resample(t, X, fs, maxSec)
%RPPG_RESAMPLE 불규칙한 프레임 시간 t의 신호 X(행=샘플)를 고정 fs로 선형 보간
%   마지막 샘플 시각에 맞춰 끝을 정렬하고, 최근 maxSec초(생략 시 전체)만 사용 — 앱과 동일
t = t(:);
span = t(end) - t(1);
if nargin >= 4, span = min(span, maxSec); end
n = floor(span * fs);
tStart = t(end) - (n - 1) / fs;
tu = tStart + (0:n-1).' / fs;
Y = interp1(t, X, tu, 'linear');
end
