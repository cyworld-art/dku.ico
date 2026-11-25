package com.lim.rppg

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/** 데이터 이용 안내 (Health Connect 권한 근거 화면으로도 사용) */
class PrivacyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val c = scaffold("데이터 이용 안내")
        c.addView(heading("카메라"))
        c.addView(body("얼굴 영상은 휴대폰 안에서만 실시간 분석되며 저장·전송되지 않습니다. 피부색의 평균값만 계산에 사용합니다."))
        c.addView(heading("워치 심박 (BLE / Health Connect)"))
        c.addView(body("측정 중 rPPG 결과와 비교하기 위해서만 심박수를 읽습니다. 다른 건강 데이터는 읽지 않으며 외부로 보내지 않습니다."))
        c.addView(heading("프로필·체질 설문"))
        c.addView(body("나이·성별·키·몸무게·설문 결과는 이 휴대폰의 앱 저장소에만 보관됩니다. 앱을 삭제하면 함께 지워집니다."))
        c.addView(heading("측정 데이터 저장"))
        c.addView(body("[저장]을 누르거나 자동 저장을 켠 경우에만 '다운로드/rPPG' 폴더에 3개 파일을 만듭니다: " +
            "_raw.csv(프레임별 피부색 평균값, 영상 아님), _hr.csv(rPPG·워치 심박), _summary.txt(결과·프로필 요약). " +
            "파일은 휴대폰에만 저장되며, 필요 없으면 파일 앱에서 지우면 됩니다."))
        c.addView(heading("주의"))
        c.addView(body("심박·스트레스·체질 결과는 연구·참고용 추정치이며 의료적 진단이 아닙니다."))
    }
}
