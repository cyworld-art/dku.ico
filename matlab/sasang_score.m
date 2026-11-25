function [pct, topName, names] = sasang_score(answers, bmi)
%SASANG_SCORE 앱의 사상체질 간이 설문 채점 (Constitution.kt와 동일)
%   answers : 12개 문항의 선택 번호(1부터, 앱 화면의 위→아래 순서)
%   bmi     : 체질량지수 (없으면 [] 또는 생략)
%   pct     : [태양 소양 태음 소음] 백분율 (합 100)
%   예) [pct, top] = sasang_score([4 4 2 3 2 2 2 4 4 4 4 2], 20.5)
names = {'태양인', '소양인', '태음인', '소음인'};
% 각 문항: 행 = 선택지, 열 = [태양 소양 태음 소음] 점수
Q = {
    [3 0 0 0; 0 3 0 0; 0 0 3 0; 0 0 0 3]   %  1 체형
    [3 0 0 0; 0 3 0 0; 0 0 3 0; 0 0 0 3]   %  2 성격
    [0 0 3 0; 0 0 0 3; 1 2 0 0]            %  3 땀
    [0 2 1 0; 0 0 3 0; 0 0 0 3; 2 0 0 1]   %  4 소화
    [0 3 1 0; 0 0 0 3; 1 0 1 0]            %  5 더위/추위
    [1 2 0 0; 0 0 0 3; 0 0 2 0]            %  6 물·음식 온도
    [0 0 3 0; 0 1 0 2; 1 1 0 0]            %  7 체중 변화
    [3 0 0 0; 0 3 0 0; 0 0 3 0; 0 0 0 3]   %  8 말투·행동
    [3 0 0 0; 0 3 0 0; 0 0 3 0; 0 0 0 3]   %  9 스트레스 반응
    [3 0 0 0; 0 3 0 0; 0 0 3 0; 0 0 0 3]   % 10 일 처리
    [0 3 0 0; 0 0 3 0; 0 0 0 2; 0 0 0 3]   % 11 대변
    [0 0 3 0; 0 0 0 3; 1 3 0 0]            % 12 피부
    };
s = zeros(1, 4);
for q = 1:numel(Q)
    if q <= numel(answers) && answers(q) >= 1 && answers(q) <= size(Q{q}, 1)
        s = s + Q{q}(answers(q), :);
    end
end
if nargin >= 2 && ~isempty(bmi)
    if bmi >= 25
        s(3) = s(3) + 3;
    elseif bmi < 19
        s(4) = s(4) + 2;
    end
end
s(1) = s(1) * 0.7;                % 태양인은 매우 드문 체질 → 사전 가중치
if sum(s) <= 0
    pct = [25 25 25 25];
else
    pct = round(s / sum(s) * 100);
    [~, k] = max(pct);
    pct(k) = pct(k) + 100 - sum(pct);
end
[~, k] = max(pct);
topName = names{k};
end
