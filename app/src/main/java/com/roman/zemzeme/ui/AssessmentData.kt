package com.roman.zemzeme.ui

import com.roman.zemzeme.R

data class Option(val textEnRes: Int, val textFaRes: Int, val score: Int)
data class Question(val textEnRes: Int, val textFaRes: Int, val options: List<Option>, val isCritical: Boolean = false)
data class AssessmentResult(val scoreRange: IntRange, val severityEnRes: Int, val severityFaRes: Int, val nextStepsEnRes: Int, val nextStepsFaRes: Int)
data class ClinicalAssessment(
    val id: String,
    val imageRes: Int? = null,
    val titleEn: String,
    val titleFa: String,
    val descriptionEn: String,
    val descriptionFa: String,
    val questions: List<Question>,
    val results: List<AssessmentResult>
)

val standardOptions = listOf(
    Option(R.string.opt_not_at_all_en, R.string.opt_not_at_all_fa, 0),
    Option(R.string.opt_several_days_en, R.string.opt_several_days_fa, 1),
    Option(R.string.opt_more_than_half_en, R.string.opt_more_than_half_fa, 2),
    Option(R.string.opt_nearly_every_en, R.string.opt_nearly_every_fa, 3)
)

val gad7 = ClinicalAssessment(
    id = "GAD-7",
    titleEn = "GAD-7 (General Anxiety Disorder-7)",
    titleFa = "پرسشنامه اضطراب فراگیر (GAD-7)",
    descriptionEn = "Over the past 2 weeks, how often have you been bothered by the following?",
    descriptionFa = "در طی ۲ هفته گذشته، هر چند وقت یک‌بار با مشکلات زیر مواجه بوده‌اید؟",
    questions = listOf(
        Question(R.string.gad7_q1_en, R.string.gad7_q1_fa, standardOptions),
        Question(R.string.gad7_q2_en, R.string.gad7_q2_fa, standardOptions),
        Question(R.string.gad7_q3_en, R.string.gad7_q3_fa, standardOptions),
        Question(R.string.gad7_q4_en, R.string.gad7_q4_fa, standardOptions),
        Question(R.string.gad7_q5_en, R.string.gad7_q5_fa, standardOptions),
        Question(R.string.gad7_q6_en, R.string.gad7_q6_fa, standardOptions),
        Question(R.string.gad7_q7_en, R.string.gad7_q7_fa, standardOptions)
    ),
    results = listOf(
        AssessmentResult(0..4, R.string.severity_no_anxiety_en, R.string.severity_no_anxiety_fa, R.string.steps_monitor_en, R.string.steps_monitor_fa),
        AssessmentResult(5..9, R.string.severity_mild_en, R.string.severity_mild_fa, R.string.steps_monitor_symptom_en, R.string.steps_monitor_symptom_fa),
        AssessmentResult(10..14, R.string.severity_moderate_en, R.string.severity_moderate_fa, R.string.steps_clinical_en, R.string.steps_clinical_fa),
        AssessmentResult(15..21, R.string.severity_severe_en, R.string.severity_severe_fa, R.string.steps_referral_en, R.string.steps_referral_fa)
    )
)

val phq9 = ClinicalAssessment(
    id = "PHQ-9",
    titleEn = "PHQ-9 (Patient Health Questionnaire-9)",
    titleFa = "پرسشنامه سلامت بیمار (PHQ-9)",
    descriptionEn = "Over the past 2 weeks, how often have you been bothered by the following?",
    descriptionFa = "در طی ۲ هفته گذشته، هر چند وقت یک‌بار با مشکلات زیر مواجه بوده‌اید؟",
    questions = listOf(
        Question(R.string.phq9_q1_en, R.string.phq9_q1_fa, standardOptions),
        Question(R.string.phq9_q2_en, R.string.phq9_q2_fa, standardOptions),
        Question(R.string.phq9_q3_en, R.string.phq9_q3_fa, standardOptions),
        Question(R.string.phq9_q4_en, R.string.phq9_q4_fa, standardOptions),
        Question(R.string.phq9_q5_en, R.string.phq9_q5_fa, standardOptions),
        Question(R.string.phq9_q6_en, R.string.phq9_q6_fa, standardOptions),
        Question(R.string.phq9_q7_en, R.string.phq9_q7_fa, standardOptions),
        Question(R.string.phq9_q8_en, R.string.phq9_q8_fa, standardOptions),
        Question(R.string.phq9_q9_en, R.string.phq9_q9_fa, standardOptions, isCritical = true)
    ),
    results = listOf(
        AssessmentResult(0..4, R.string.severity_minimal_en, R.string.severity_minimal_fa, R.string.steps_monitor_en, R.string.steps_monitor_fa),
        AssessmentResult(5..9, R.string.severity_mild_en, R.string.severity_mild_fa, R.string.steps_clinical_en, R.string.steps_clinical_fa),
        AssessmentResult(10..14, R.string.severity_moderate_en, R.string.severity_moderate_fa, R.string.steps_clinical_duration_en, R.string.steps_clinical_duration_fa),
        AssessmentResult(15..19, R.string.severity_moderately_severe_en, R.string.severity_moderately_severe_fa, R.string.steps_active_treatment_en, R.string.steps_active_treatment_fa),
        AssessmentResult(20..27, R.string.severity_severe_en, R.string.severity_severe_fa, R.string.steps_active_treatment_en, R.string.steps_active_treatment_fa)
    )
)
