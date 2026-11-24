package com.lim.rppg

import kotlin.math.roundToInt

enum class Sasang(val label: String, val summary: String, val stressTip: String) {
    TAEYANG(
        "태양인",
        "폐 기능이 강하고 간 기능이 약한 체질. 진취적·독창적이며 결단력이 있음. 하체가 약하기 쉬움.",
        "분노가 쌓이면 간에 부담이 됩니다. 화를 바로 다스리고, 담백한 음식과 하체 운동을 권합니다.",
    ),
    SOYANG(
        "소양인",
        "비(소화) 기능이 강하고 신 기능이 약한 체질. 활발하고 급하며 열이 많음.",
        "급한 성격이 몸의 열을 올립니다. 심호흡과 충분한 수분, 맵고 기름진 음식 줄이기를 권합니다.",
    ),
    TAEEUM(
        "태음인",
        "간 기능이 강하고 폐 기능이 약한 체질. 느긋하고 끈기 있으며 체격이 크고 살이 잘 찜.",
        "스트레스를 먹는 것으로 풀기 쉽습니다. 땀이 날 정도의 유산소 운동으로 해소하세요.",
    ),
    SOEUM(
        "소음인",
        "신 기능이 강하고 비(소화) 기능이 약한 체질. 꼼꼼하고 내성적이며 몸이 찬 편.",
        "긴장하면 소화가 먼저 막힙니다. 따뜻한 음식, 규칙적인 식사, 가벼운 산책을 권합니다.",
    ),
}

class CQuestion(val text: String, val options: List<Pair<String, IntArray>>)

/**
 * 간이 사상체질 설문 (참고용).
 * 점수 순서: [태양, 소양, 태음, 소음]. 공식 진단도구(QSCC II 등)를 대체하지 않습니다.
 */
object Constitution {
    private fun o(text: String, ty: Int, sy: Int, te: Int, se: Int) = text to intArrayOf(ty, sy, te, se)

    val questions = listOf(
        CQuestion("체형은 어느 쪽에 가깝나요?", listOf(
            o("목덜미·머리 쪽이 발달, 허리 아래는 약한 편", 3, 0, 0, 0),
            o("가슴·어깨가 발달, 엉덩이는 빈약한 편", 0, 3, 0, 0),
            o("허리·배가 발달, 체격이 크고 단단함", 0, 0, 3, 0),
            o("엉덩이가 발달, 가슴이 좁고 체격이 작은 편", 0, 0, 0, 3),
        )),
        CQuestion("평소 성격은?", listOf(
            o("진취적·독창적, 간섭을 싫어함", 3, 0, 0, 0),
            o("활발하고 급하며 일을 빨리 벌임", 0, 3, 0, 0),
            o("느긋하고 꾸준하며 속을 잘 드러내지 않음", 0, 0, 3, 0),
            o("꼼꼼하고 내성적이며 생각이 많음", 0, 0, 0, 3),
        )),
        CQuestion("땀은 어떤가요?", listOf(
            o("땀이 많고, 흘리고 나면 개운하다", 0, 0, 3, 0),
            o("땀이 적고, 많이 흘리면 기운이 빠진다", 0, 0, 0, 3),
            o("보통이며 주로 상체·얼굴에 난다", 1, 2, 0, 0),
        )),
        CQuestion("소화 기능은?", listOf(
            o("소화가 잘 되고 식사 속도가 빠르다", 0, 2, 1, 0),
            o("식욕이 좋아 과식하기 쉽다", 0, 0, 3, 0),
            o("소화가 약해 쉽게 체하거나 더부룩하다", 0, 0, 0, 3),
            o("조금만 먹어도 되고 구역감이 잦다", 2, 0, 0, 1),
        )),
        CQuestion("더위와 추위 중 어느 쪽을 타나요?", listOf(
            o("더위를 많이 타고 시원한 것이 좋다", 0, 3, 1, 0),
            o("추위를 많이 타고 손발이 차다", 0, 0, 0, 3),
            o("크게 가리지 않는다", 1, 0, 1, 0),
        )),
        CQuestion("선호하는 물·음식 온도는?", listOf(
            o("찬물·찬 음식이 좋다", 1, 2, 0, 0),
            o("따뜻한 물·음식이 편하다", 0, 0, 0, 3),
            o("상관없고 물을 많이 마신다", 0, 0, 2, 0),
        )),
        CQuestion("체중 변화는?", listOf(
            o("살이 잘 찌고 잘 안 빠진다", 0, 0, 3, 0),
            o("잘 안 찌고 마른 편이다", 0, 1, 0, 2),
            o("보통이다", 1, 1, 0, 0),
        )),
        CQuestion("말투와 행동은?", listOf(
            o("결단력 있고 거침없다", 3, 0, 0, 0),
            o("빠르고 시원시원하다", 0, 3, 0, 0),
            o("무게감 있고 점잖다", 0, 0, 3, 0),
            o("조용하고 차분하며 말수가 적다", 0, 0, 0, 3),
        )),
        CQuestion("스트레스를 받으면?", listOf(
            o("분노가 치밀고 참기 어렵다", 3, 0, 0, 0),
            o("화를 내지만 금방 풀린다", 0, 3, 0, 0),
            o("걱정이 많아지고 먹는 것으로 푼다", 0, 0, 3, 0),
            o("속으로 삭이고 오래 간다", 0, 0, 0, 3),
        )),
        CQuestion("일 처리 방식은?", listOf(
            o("새 방식을 만들고 앞장선다", 3, 0, 0, 0),
            o("시작은 잘하나 마무리가 약하다", 0, 3, 0, 0),
            o("느리지만 끈기 있게 해낸다", 0, 0, 3, 0),
            o("계획적이고 꼼꼼하게 끝낸다", 0, 0, 0, 3),
        )),
        CQuestion("대변은?", listOf(
            o("변비가 생기면 매우 답답하고 불편하다", 0, 3, 0, 0),
            o("굵고 시원하게 보면 몸이 가볍다", 0, 0, 3, 0),
            o("변비가 며칠 가도 크게 불편하지 않다", 0, 0, 0, 2),
            o("설사를 하면 기운이 쭉 빠진다", 0, 0, 0, 3),
        )),
        CQuestion("피부는?", listOf(
            o("두껍고 땀구멍이 크다", 0, 0, 3, 0),
            o("부드럽고 얇은 편이다", 0, 0, 0, 3),
            o("건조하고 열이 잘 오른다", 1, 3, 0, 0),
        )),
    )

    /** @param answers 각 문항의 선택 인덱스 @return 체질별 백분율(합 100) */
    fun score(answers: IntArray, bmi: Double?): IntArray {
        val s = DoubleArray(4)
        questions.forEachIndexed { qi, q ->
            val a = answers.getOrNull(qi) ?: -1
            if (a in q.options.indices) q.options[a].second.forEachIndexed { k, v -> s[k] += v.toDouble() }
        }
        if (bmi != null) {
            if (bmi >= 25) s[2] += 3.0
            else if (bmi < 19) s[3] += 2.0
        }
        s[0] *= 0.7 // 태양인은 매우 드문 체질 → 사전 가중치
        val sum = s.sum().takeIf { it > 0 } ?: return intArrayOf(25, 25, 25, 25)
        val pct = IntArray(4) { (s[it] / sum * 100).roundToInt() }
        pct[pct.indices.maxByOrNull { pct[it] }!!] += 100 - pct.sum()
        return pct
    }

    fun top(pct: IntArray): Sasang = Sasang.values()[pct.indices.maxByOrNull { pct[it] }!!]

    fun isBorderline(pct: IntArray): Boolean {
        val s = pct.sortedDescending()
        return s[0] - s[1] < 10
    }
}
