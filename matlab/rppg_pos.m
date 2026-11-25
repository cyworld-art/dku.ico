function H = rppg_pos(R, G, B, fs)
%RPPG_POS Plane-Orthogonal-to-Skin 맥파 추출 (Wang et al., IEEE TBME 2017)
%   1.6초 창마다: 채널별 시간 정규화 → S1 = G−B, S2 = −2R+G+B
%   → h = S1 + (σ1/σ2)·S2 → 평균 제거 후 중첩 가산
R = R(:); G = G(:); B = B(:);
n = numel(R);
L = floor(1.6 * fs);
H = zeros(n, 1);
for e = L:n
    s = e - L + 1;
    C = [R(s:e), G(s:e), B(s:e)];
    m = mean(C, 1);
    if any(m <= 0), continue; end
    Cn = C ./ m;
    S1 = Cn(:,2) - Cn(:,3);
    S2 = -2*Cn(:,1) + Cn(:,2) + Cn(:,3);
    sd2 = std(S2, 1);                 % 모집단 표준편차 (앱과 동일)
    if sd2 > 1e-12, a = std(S1, 1) / sd2; else, a = 0; end
    h = S1 + a * S2;
    H(s:e) = H(s:e) + (h - mean(h));
end
end
