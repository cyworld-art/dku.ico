function C = rppg_compare(tr, br, tw, bw, tol)
%RPPG_COMPARE rPPG 각 시점과 가장 가까운 워치 값(±tol초 이내)을 짝지어 비교
%   tol: BLE 3초, Health Connect 30초 (앱과 동일)
diffs = [];
for i = 1:numel(tr)
    if isnan(br(i)) || isempty(tw), continue; end
    [d, j] = min(abs(tw - tr(i)));
    if d <= tol
        diffs(end+1) = br(i) - bw(j); %#ok<AGROW>
    end
end
C = struct('n', numel(diffs), 'mae', mean(abs(diffs)), 'bias', mean(diffs), ...
    'rmse', sqrt(mean(diffs.^2)), 'diffs', diffs(:));
if isempty(diffs), C.mae = NaN; C.bias = NaN; C.rmse = NaN; end
end
