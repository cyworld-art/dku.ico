function [t, wall, rgb, face] = rppg_load_raw(file)
%RPPG_LOAD_RAW 앱이 저장한 *_raw.csv 읽기
%   열: t_s    카메라 센서 타임스탬프(초, 첫 프레임 = 0)
%       wall_s 측정 시작 버튼 기준 경과 시간(초, 워치 데이터와 같은 시간축)
%       R,G,B  이마+양볼 ROI 평균 색 (YUV→RGB, 0–255)
%       face   1 = 얼굴 검출, 0 = 미검출(이때 RGB = 0)
M = rppg_read_csv(file);
t = M(:,1); wall = M(:,2); rgb = M(:,3:5); face = M(:,6) > 0;
end
