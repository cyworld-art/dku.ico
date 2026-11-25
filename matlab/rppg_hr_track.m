function T = rppg_hr_track(t, rgb, fs)
%RPPG_HR_TRACK 앱의 실시간 동작 재현
%   0.5초마다 최근 10초(초기엔 보유분, 최소 5초) → 재샘플링 → POS → 0.7–3 Hz → 스펙트럼 피크.
%   SNR > −3 dB 추정치만 모아 최근 5개의 중앙값을 화면 표시값(shown)으로 사용.
%   반환: T.t(구간 시작 기준 초), T.bpm(순간 추정), T.snr, T.shown(표시값)
t = t(:); t0 = t(1);
tt = []; bpm = []; snr = []; shown = []; recent = [];
for te = (t0 + 5):0.5:t(end)
    iEnd = find(t <= te, 1, 'last');
    iSt = find(t >= t(iEnd) - 10.2, 1, 'first');
    [~, Y] = rppg_resample(t(iSt:iEnd), rgb(iSt:iEnd,:), fs, 10);
    if size(Y,1) < 5*fs - 1, continue; end
    H = rppg_pos(Y(:,1), Y(:,2), Y(:,3), fs);
    h = rppg_bandpass(H, fs, 0.7, 3.0);
    [f0, s] = rppg_spectral_peak(h, fs, 0.7, 3.0);
    if s > -3
        recent(end+1) = f0*60; %#ok<AGROW>
        if numel(recent) > 5, recent(1) = []; end
    end
    if isempty(recent)
        m = NaN;
    else
        srt = sort(recent); m = srt(floor(numel(srt)/2) + 1);
    end
    tt(end+1) = te - t0; bpm(end+1) = f0*60; snr(end+1) = s; shown(end+1) = m; %#ok<AGROW>
end
T = struct('t', tt(:), 'bpm', bpm(:), 'snr', snr(:), 'shown', shown(:));
end
