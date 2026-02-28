package com.roman.zemzeme.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.roman.zemzeme.R

@Composable
fun AssessmentScreen(onBack: () -> Unit) {
    var isFarsi by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var selectedAssessment by rememberSaveable { mutableStateOf<ClinicalAssessment?>(null) }
    var showIncompleteError by rememberSaveable { mutableStateOf(false) }
    var hasAcceptedDisclaimer by rememberSaveable { mutableStateOf(false) }
    val mapSaver = Saver<MutableMap<Int, Int>, IntArray>(
        save = { map -> map.flatMap { listOf(it.key, it.value) }.toIntArray() },
        restore = { array ->
            val map = mutableStateMapOf<Int, Int>()
            for (i in array.indices step 2) { map[array[i]] = array[i + 1] }
            map
        }
    )
    val answers = rememberSaveable(saver = mapSaver) { mutableStateMapOf<Int, Int>() }
    val context = LocalContext.current
    val layoutDirection = if (isFarsi) LayoutDirection.Rtl else LayoutDirection.Ltr

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.lang_en))
                Switch(checked = isFarsi, onCheckedChange = { isFarsi = it }, modifier = Modifier.padding(horizontal = 8.dp))
                Text(stringResource(R.string.lang_fa))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
            if (step == 0) {
                Text(text = if (isFarsi) stringResource(R.string.assessment_welcome_fa) else stringResource(R.string.assessment_welcome_en), style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = if (isFarsi) stringResource(R.string.assessment_intro_fa) else stringResource(R.string.assessment_intro_en), style = MaterialTheme.typography.bodyLarge)
                Spacer(modifier = Modifier.height(24.dp))
                
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { hasAcceptedDisclaimer = !hasAcceptedDisclaimer }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = hasAcceptedDisclaimer, onCheckedChange = { hasAcceptedDisclaimer = it })
                    Text(
                        text = if (isFarsi) stringResource(R.string.disclaimer_accept_fa) else stringResource(R.string.disclaimer_accept_en),
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                Button(onClick = { step = 1 }, enabled = hasAcceptedDisclaimer, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isFarsi) stringResource(R.string.assessment_start_fa) else stringResource(R.string.assessment_start_en))
                }
            } else if (step == 1) {
                Text(text = if (isFarsi) stringResource(R.string.assessment_selection_title_fa) else stringResource(R.string.assessment_selection_title_en), style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(24.dp))
                Button(onClick = { selectedAssessment = gad7; step = 2 }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isFarsi) stringResource(R.string.assessment_gad7_btn_fa) else stringResource(R.string.assessment_gad7_btn_en))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { selectedAssessment = phq9; step = 2 }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (isFarsi) stringResource(R.string.assessment_phq9_btn_fa) else stringResource(R.string.assessment_phq9_btn_en))
                }
            } else if (step == 2) {
                selectedAssessment?.let { assessment ->
                    Text(text = if (isFarsi) assessment.titleFa else assessment.titleEn, style = MaterialTheme.typography.headlineMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(if (isFarsi) assessment.descriptionFa else assessment.descriptionEn)
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        itemsIndexed(assessment.questions) { index, question ->
                            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(text = "${index + 1}. ${if (isFarsi) stringResource(question.textFaRes) else stringResource(question.textEnRes)}")
                                    question.options.forEach { option ->
                                        val optionText = if (isFarsi) stringResource(option.textFaRes) else stringResource(option.textEnRes)
                                        Row(
                                            modifier = Modifier.fillMaxWidth().selectable(
                                                selected = (answers[index] == option.score),
                                                onClick = { answers[index] = option.score; showIncompleteError = false },
                                                role = Role.RadioButton
                                            ).semantics { contentDescription = optionText }.padding(vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(selected = (answers[index] == option.score), onClick = null)
                                            Text(text = optionText, modifier = Modifier.padding(start = 8.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (showIncompleteError) {
                        Text(text = if (isFarsi) stringResource(R.string.assessment_incomplete_error_fa) else stringResource(R.string.assessment_incomplete_error_en), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    Button(
                        onClick = {
                            val requiredIndices = assessment.questions.indices.toList()
                            if (answers.keys.containsAll(requiredIndices)) {
                                showIncompleteError = false
                                step = 3
                            } else {
                                showIncompleteError = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (isFarsi) stringResource(R.string.assessment_view_results_fa) else stringResource(R.string.assessment_view_results_en)) }
                }
            } else if (step == 3) {
                selectedAssessment?.let { assessment ->
                    val totalScore = answers.values.sum()
                    val resultTier = assessment.results.firstOrNull { totalScore in it.scoreRange }
                    val triggeredCritical = assessment.questions.mapIndexedNotNull { index, q -> if (q.isCritical && (answers[index] ?: 0) > 0) true else null }.isNotEmpty()
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        Text(text = if (isFarsi) stringResource(R.string.assessment_total_score_fa, totalScore) else stringResource(R.string.assessment_total_score_en, totalScore), style = MaterialTheme.typography.displaySmall)
                        Spacer(modifier = Modifier.height(16.dp))
                        if (resultTier != null) {
                            Text(text = if (isFarsi) stringResource(R.string.assessment_severity_fa, stringResource(resultTier.severityFaRes)) else stringResource(R.string.assessment_severity_en, stringResource(resultTier.severityEnRes)), style = MaterialTheme.typography.headlineSmall)
                            Text(if (isFarsi) stringResource(R.string.assessment_next_steps_fa, stringResource(resultTier.nextStepsFaRes)) else stringResource(R.string.assessment_next_steps_en, stringResource(resultTier.nextStepsEnRes)))
                        }
                        if (triggeredCritical) {
                            Spacer(modifier = Modifier.height(24.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error)) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(text = if (isFarsi) stringResource(R.string.assessment_critical_warning_fa) else stringResource(R.string.assessment_critical_warning_en), color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (isFarsi) {
                                                Button(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1480"))) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.assessment_call_1480), color = MaterialTheme.colorScheme.onError) }
                                                Button(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:123"))) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.assessment_call_123), color = MaterialTheme.colorScheme.onError) }
                                                Button(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:115"))) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.assessment_call_115), color = MaterialTheme.colorScheme.onError) }
                                            } else {
                                                Button(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:988"))) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.assessment_call_988), color = MaterialTheme.colorScheme.onError) }
                                                Button(onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:911"))) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.assessment_call_911), color = MaterialTheme.colorScheme.onError) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Button(onClick = { 
                        answers.clear()
                        step = 0
                        hasAcceptedDisclaimer = false
                        selectedAssessment = null
                        onBack() 
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (isFarsi) stringResource(R.string.assessment_finish_back_fa) else stringResource(R.string.assessment_finish_back_en))
                    }
                }
            }
        }
    }
}
