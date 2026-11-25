function [rp, wt] = rppg_load_hr(file)
%RPPG_LOAD_HR 앱이 저장한 *_hr.csv 읽기
%   열: t_s(측정 시작 기준 초), bpm, source(1 = 앱의 rPPG 표시값, 2 = 워치)
%   rp, wt : 각각 [t, bpm] 행렬 (없으면 0x2)
rp = zeros(0,2); wt = zeros(0,2);
if ~isfile(file), return; end
M = rppg_read_csv(file);
if isempty(M), return; end
rp = M(M(:,3) == 1, 1:2);
wt = sortrows(M(M(:,3) == 2, 1:2), 1);
end
