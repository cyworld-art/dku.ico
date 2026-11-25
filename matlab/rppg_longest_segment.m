function idx = rppg_longest_segment(t, face, maxGap)
%RPPG_LONGEST_SEGMENT 얼굴이 계속 검출되고 프레임 간격이 maxGap 이하인 가장 긴 연속 구간
%   앱도 얼굴을 놓치거나 0.5초 이상 끊기면 신호 버퍼를 처음부터 다시 쌓습니다.
if nargin < 3, maxGap = 0.5; end
n = numel(t);
startIdx = NaN; prev = 0; bestLen = -1; bestS = 1; bestE = 0;
for i = 1:n
    if ~face(i)
        startIdx = NaN;
        continue;
    end
    if isnan(startIdx) || t(i) - t(prev) > maxGap || t(i) <= t(prev)
        startIdx = i;
    end
    prev = i;
    len = t(i) - t(startIdx);
    if len > bestLen
        bestLen = len; bestS = startIdx; bestE = i;
    end
end
idx = (bestS:bestE).';
end
