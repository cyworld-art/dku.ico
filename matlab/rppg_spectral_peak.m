function [f0, snrDb, f, P] = rppg_spectral_peak(x, fs, lo, hi)
%RPPG_SPECTRAL_PEAK Hann 창 + 0.5–4.0 Hz(0.01 Hz 간격) DFT로 심박 주파수 추정
%   피크는 [lo, hi]에서 찾고 포물선 보간으로 정밀화.
%   SNR(de Haan 2013): (기본파 ±0.1 Hz + 2고조파 ±0.2 Hz 파워) / 나머지 파워 [dB]
x = x(:); n = numel(x);
w = x .* (0.5 - 0.5*cos(2*pi*(0:n-1).' / (n-1)));
step = 0.01;
f = 0.5 + step*(0:350).';
P = abs(exp(-1i*2*pi*(f/fs)*(0:n-1)) * w).^2;
band = find(f >= lo & f <= hi);
[~, j] = max(P(band)); b = band(j);
f0 = f(b);
if b > 1 && b < numel(f)
    A = P(b-1); Bv = P(b); C = P(b+1); d = A - 2*Bv + C;
    if abs(d) > 1e-20
        f0 = f0 + step * max(-0.5, min(0.5, 0.5*(A - C)/d));
    end
end
sig = abs(f - f0) <= 0.1 | abs(f - 2*f0) <= 0.2;
snrDb = 10*log10(sum(P(sig)) / sum(P(~sig)));
end
