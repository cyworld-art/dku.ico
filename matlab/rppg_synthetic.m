function [t, wall, rgb, face, truth] = rppg_synthetic(hrBpm, durSec, fs, noise)
%RPPG_SYNTHETIC 알고리즘 검증용 합성 얼굴 색 신호
%   RSA(호흡성 변동)가 있는 박동열 → 맥파(수축기 + 중복파) → 피부 RGB 변조 + 잡음
%   프레임 간격은 28–40 ms로 불규칙 (실제 카메라처럼)
if nargin < 1, hrBpm = 72; end
if nargin < 2, durSec = 60; end
if nargin < 3, fs = 30; end
if nargin < 4, noise = 0.1; end
rng(1);
mrr = 60 / hrBpm;
nb = ceil(durSec / mrr) + 10;
rrs = mrr + 0.03*randn(nb,1) + 0.04*sin((1:nb).' * 0.9);
beats = cumsum(rrs); beats = beats(beats < durSec + 1);
t = cumsum(0.028 + 0.012*rand(ceil(durSec*fs*1.3), 1)); t = t - t(1);
t = t(t <= durSec);
pulse = zeros(size(t));
for b = beats.'
    pulse = pulse + exp(-((t - b)/0.08).^2) + 0.4*exp(-((t - b - 0.3)/0.1).^2);
end
base = [170 120 100];
pv = [0.33 0.77 0.53] * 0.006;            % 혈액량 변화에 따른 채널별 상대 진폭
illum = 1 + 0.03*sin(2*pi*0.05*t);        % 느린 조명 변화
rgb = (base .* (1 + pv .* pulse)) .* illum + noise*randn(numel(t), 3);
wall = t; face = true(size(t));
truth = struct('beats', beats, 'rr', diff(beats), 'hrBpm', 60/mean(diff(beats)), ...
    'sdnnMs', std(diff(beats))*1000, 'rmssdMs', sqrt(mean(diff(diff(beats)).^2))*1000);
end
