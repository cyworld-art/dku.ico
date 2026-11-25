function M = rppg_read_csv(file)
%RPPG_READ_CSV 헤더 1줄이 있는 숫자 CSV 읽기 (readmatrix 없으면 csvread 사용)
if ~isfile(file)
    error('rppg:file', '파일이 없습니다: %s', file);
end
try
    M = readmatrix(file, 'NumHeaderLines', 1);
catch
    M = csvread(file, 1, 0); %#ok<CSVRD>
end
end
