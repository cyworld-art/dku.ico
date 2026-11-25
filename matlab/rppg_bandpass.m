function y = rppg_bandpass(x, fs, lo, hi)
%RPPG_BANDPASS 2차 버터워스 고역통과(lo) → 저역통과(hi), 각각 정·역방향 2회 적용(zero-phase)
%   RBJ cookbook 계수, 첫 샘플로 필터 상태를 채우는 초기화까지 앱과 동일.
%   Signal Processing Toolbox 불필요.
x = x(:);
if isempty(x), y = x; return; end
[bh, ah] = rbj_biquad('hp', lo, fs);
[bl, al] = rbj_biquad('lp', hi, fs);
y = filtfilt_biquad(bh, ah, x);
y = filtfilt_biquad(bl, al, y);
end

function [b, a] = rbj_biquad(type, fc, fs)
Q = 1/sqrt(2);
w0 = 2*pi*fc/fs; al = sin(w0)/(2*Q); c = cos(w0); a0 = 1 + al;
switch type
    case 'lp', b = [(1-c)/2, 1-c, (1-c)/2] / a0;
    case 'hp', b = [(1+c)/2, -(1+c), (1+c)/2] / a0;
    otherwise, error('rppg:type', 'lp 또는 hp');
end
a = [1, -2*c/a0, (1-al)/a0];
end

function y = filtfilt_biquad(b, a, x)
y = biquad_apply(b, a, x);
y = flipud(biquad_apply(b, a, flipud(y)));
end

function y = biquad_apply(b, a, x)
n = numel(x); y = zeros(n, 1);
x1 = x(1); x2 = x1;
dc = sum(b) / sum(a);         % 정상상태 이득 → 시작 과도응답 최소화
y1 = x1 * dc; y2 = y1;
for i = 1:n
    v = b(1)*x(i) + b(2)*x1 + b(3)*x2 - a(2)*y1 - a(3)*y2;
    x2 = x1; x1 = x(i); y2 = y1; y1 = v;
    y(i) = v;
end
end
